package com.synergic.cloakroom.data.repository

import android.util.Base64
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.remote.MastersApi
import com.synergic.cloakroom.data.remote.ReportBagDto
import com.synergic.cloakroom.data.remote.ReportQuery
import com.synergic.cloakroom.data.remote.ReportResult
import com.synergic.cloakroom.data.remote.ReportsApi
import com.synergic.cloakroom.data.remote.SyncExceptionDto
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.auth.UserTypes
import com.synergic.cloakroom.domain.billing.money
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.remote.ServerException
import com.synergic.cloakroom.domain.report.BillRow
import com.synergic.cloakroom.domain.report.CheckinRow
import com.synergic.cloakroom.domain.report.CollectionLine
import com.synergic.cloakroom.domain.report.DashboardData
import com.synergic.cloakroom.domain.report.ReportAccess
import com.synergic.cloakroom.domain.report.ReportData
import com.synergic.cloakroom.domain.report.ReportFilter
import com.synergic.cloakroom.domain.report.ReportShift
import com.synergic.cloakroom.domain.report.ReportUser
import com.synergic.cloakroom.domain.report.SyncExceptionRow
import com.synergic.cloakroom.domain.repository.ReportRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.repository.SuperAdminRepository
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the server reports for the station admin's own customer (null) or a customer the super admin opened. */
@Singleton
class RemoteReportRepositoryFactory @Inject constructor(
    private val api: ReportsApi,
    private val mastersApi: MastersApi,
    private val superAdmin: SuperAdminRepository,
    private val db: AppDatabase,
    private val sessionProvider: SessionProvider,
    private val settingsRepository: SettingsRepository,
    private val local: ReportRepositoryImpl,
    private val time: TimeSource,
) {
    /** The station admin's: one instance, so the report password typed stays for the session. */
    val own: RemoteReportRepository by lazy { create(null) }

    fun create(customerId: Long?) =
        RemoteReportRepository(api, mastersApi, superAdmin, db, sessionProvider, settingsRepository, local, time, customerId)
}

/**
 * Dashboard, bags in store and reports from the server (API_GUIDE.md §9): the station admin's own
 * customer ([customerId] null; works online only, your rule) or, for the super admin, the
 * customer opened ([customerId], sent as `customer_id`) — the same screens either way. Screens
 * refresh every [REFRESH_MS] while open. The admin's user / shift filters come from the login /
 * pull copy of the masters; the super admin's from the server. The print layout is the phone's own.
 */
class RemoteReportRepository(
    private val api: ReportsApi,
    private val mastersApi: MastersApi,
    private val superAdmin: SuperAdminRepository,
    private val db: AppDatabase,
    private val sessionProvider: SessionProvider,
    private val settingsRepository: SettingsRepository,
    private val local: ReportRepositoryImpl,
    private val time: TimeSource,
    private val customerId: Long?,
) : ReportRepository {

    /** The report password typed in this session (sent as X-Report-Password), with the session it belongs to. */
    @Volatile private var password: Pair<String, String>? = null

    private fun requireAllowed(): UserSession {
        val s = sessionProvider.requireLogin()
        val ok = if (customerId != null) s.isSuperAdmin else s.isAdmin
        if (!ok) throw NotAuthorizedException("Only an admin can see all users' reports.")
        return s
    }

    private fun query(s: UserSession, from: String? = null, to: String? = null) =
        ReportQuery(from = from, to = to, customerId = customerId, reportPassword = password?.takeIf { it.first == s.sessionStartedAt }?.second)

    private fun today() = DateTimes.localDateText(time.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDashboard(): Flow<DashboardData> =
        sessionProvider.state.map { (it as? SessionState.LoggedIn)?.session }.distinctUntilChanged().flatMapLatest { s ->
            if (s == null || !s.isAdmin) return@flatMapLatest emptyFlow()
            refreshing {
                val q = query(s)
                val day = query(s, today(), today())
                val inStore = api.bagsInStore(q)
                val checkins = api.checkinRegister(day)
                val bills = api.checkoutRegister(day)
                val settings = settingsRepository.getSettings(s.id)
                DashboardData(
                    receiptsInStore = inStore.int("bags_entries"),
                    bagsInStore = inStore.int("bags"),
                    todayCheckins = checkins.int("bags_entries"),
                    todayBills = bills.int("bags_entries"),
                    // Money taken today: balances at check-out + advances at check-in.
                    todayCollection = if (settings.totalCollectionFlag) (amount(bills.money(if (bills.totals?.containsKey("bal_amt") == true) "bal_amt" else "net_amt")) + amount(checkins.money("adv_amt"))).money() else null,
                    allUsers = true,
                )
            }
        }

    /** Bags in store of all counters, refreshed while the screen is open. */
    fun observeBagsInStore(): Flow<List<CheckinRow>> = refreshing {
        val s = requireAllowed()
        val codes = userCodes()
        api.bagsInStore(query(s)).rows.map { it.toCheckin(codes) }
    }

    /** Emits [load] now and again every [REFRESH_MS]; a failed try (offline) keeps the last value. */
    private fun <T> refreshing(load: suspend () -> T): Flow<T> = flow {
        while (true) {
            try {
                emit(load())
            } catch (e: ServerException) {
                // The Online required screen covers offline; the next round tries again.
            }
            delay(REFRESH_MS)
        }
    }

    override suspend fun reportAccess(): ReportAccess {
        val s = requireAllowed()
        // The super admin's own settings say nothing about the customer's report password; when
        // the server asks for it the screen shows the password box (see report()).
        val needsPassword = customerId == null && settingsRepository.getSettings(s.id).reportPwdFlag
        return ReportAccess.Allowed(needsPassword = needsPassword, canChooseUser = true, showExceptions = true)
    }

    /** The server checks the password: a small report with the header tells whether it is right. */
    override suspend fun verifyReportPassword(password: String): Boolean {
        val s = requireAllowed()
        return try {
            api.collectionTotals(query(s, today(), today()).copy(reportPassword = password))
            this.password = s.sessionStartedAt to password
            true
        } catch (e: ServerException) {
            if (e.code == REPORT_PASSWORD_REQUIRED) false else throw e
        }
    }

    override suspend fun reportUsers(): List<ReportUser> {
        if (customerId == null) return local.reportUsers()
        requireAllowed()
        return superAdmin.users(customerId).map { ReportUser(it.id, it.userCode, it.userName) }
    }

    override suspend fun reportShifts(): List<ReportShift> {
        if (customerId == null) return local.reportShifts()
        requireAllowed()
        return superAdmin.shifts(customerId).map { ReportShift(it.id, it.name, it.fromTime, it.toTime, removed = false) }
    }

    override suspend fun report(filter: ReportFilter): ReportData {
        val s = requireAllowed()
        val (from, to) = if (filter.from <= filter.to) filter.from to filter.to else filter.to to filter.from
        val f = filter.copy(from = from, to = to)
        val q = query(s, from.toString(), to.toString()).copy(userId = f.userId, shiftId = f.shiftId)
        val codes = userCodes()
        // All calls at once: the screen waits for the slowest one only.
        return coroutineScope {
            val checkins = async { api.checkinRegister(q).rows.map { it.toCheckin(codes) } }
            val bills = async { api.checkoutRegister(q).rows.filter { it.billNo != null }.map { it.toBill(codes) } }
            val modes = PayMode.entries.map { mode ->
                mode to (async { api.collectionTotals(q.copy(payMode = mode.code)) } to async { api.gstTotals(q.copy(payMode = mode.code)) })
            }
            val all = async { api.collectionTotals(q) } to async { api.gstTotals(q) }
            val byPayMode = modes.map { (mode, calls) -> line(mode, calls.first.await(), calls.second.await()) }.filter { it.bills > 0 }
            val totals = all.first.await()
            ReportData(
                f, checkins.await(), bills.await(), byPayMode, line(null, totals, all.second.await()),
                advanceCollected = amount(totals.money("adv_collected")),
            )
        }
    }

    override suspend fun reportDocument(filter: ReportFilter): PrintDocument {
        val data = report(filter)
        if (customerId == null) return local.formatDocument(data)
        // The super admin prints with the opened customer's name and report lines.
        val name = superAdmin.summary(customerId).customer.name
        val lines = mastersApi.listHeaderFooter(customerId).filter { it.reportFlag == "Y" && it.isDeleted == "N" }.sortedBy { it.lineOrder }
        val userLabel = filter.userId?.let { id -> reportUsers().firstOrNull { it.id == id }?.let { "${it.userCode} ${it.userName}" } } ?: "All users"
        val shiftLabel = filter.shiftId?.let { id -> reportShifts().firstOrNull { it.id == id }?.label } ?: "All shifts"
        return local.formatDocument(
            data,
            ReportHeading(
                customerName = name,
                headerLines = lines.filter { it.lineType == "H" }.mapNotNull { it.narration },
                footerLines = lines.filter { it.lineType == "F" }.mapNotNull { it.narration },
                logo = lines.firstOrNull { it.lineType == "L" }?.image?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() },
                userLabel = userLabel,
                shiftLabel = shiftLabel,
            ),
        )
    }

    override suspend fun exceptions(): List<SyncExceptionRow> {
        val s = requireAllowed()
        return api.exceptions(query(s), resolved = "N").map { it.toRow() }
    }

    override suspend fun resolveException(id: Long) {
        requireAllowed()
        api.resolveException(id)
    }

    /** The server's own totals: rent / penalty / net from collection-summary, the GST breakup from gst. */
    private fun line(mode: PayMode?, c: ReportResult<Unit>, g: ReportResult<Unit>) = CollectionLine(
        payMode = mode,
        bills = c.int("bills"),
        baseAmt = amount(c.money("base_amt")),
        penaltyAmt = amount(c.money("penal_amt")),
        taxableAmt = amount(g.money("taxable_amt")),
        cgst = amount(g.money("cgst")),
        sgst = amount(g.money("sgst")),
        igst = amount(g.money("igst")),
        netAmt = amount(c.money("net_amt")),
        advAmt = amount(c.money("adv_amt")),
        // A server without the advance columns sends no bal_amt: everything was collected at check-out.
        balAmt = amount(c.money(if (c.totals?.containsKey("bal_amt") == true) "bal_amt" else "net_amt")),
    )

    private fun amount(text: String) = BigDecimal(text).money()

    /** md_user.id → user code: the phone's copy for the admin, the server's list for the super admin. */
    private suspend fun userCodes(): Map<Long, String> =
        if (customerId == null) {
            db.userDao().getAllIncludingRemoved(Tenant.CUSTOMER_ID).associate { it.id to it.userCode.orEmpty() }
        } else {
            superAdmin.users(customerId).associate { it.id to it.userCode }
        }

    private fun ReportBagDto.toCheckin(codes: Map<Long, String>) = CheckinRow(
        transId = transId, receiptNo = receiptNo, dateTimeIn = DateTimes.fromServerText(dateTimeIn),
        userCode = codes[userIdIn] ?: userNameIn.orEmpty(), name = pasnName, mobile = pasnMobNo, pnr = pnrNo,
        bags = luggageQty, extraBags = extraLugageQty, isOut = outFlag == "Y", shift = shiftNameIn.orEmpty(),
        advAmt = amount(advAmt),
    )

    /** check-out-register rows carry only the net amount; the GST breakup is in the totals. */
    private fun ReportBagDto.toBill(codes: Map<Long, String>) = BillRow(
        transId = transId, billNo = billNo.orEmpty(), billDt = billDt.orEmpty(), receiptNo = receiptNo,
        dateTimeOut = dateTimeOut?.let { DateTimes.fromServerText(it) }.orEmpty(), userCode = userIdOut?.let { codes[it] }.orEmpty(), name = pasnName, bags = luggageQty,
        extraBags = extraLugageQty,
        baseAmt = ZERO, penaltyAmt = ZERO, taxableAmt = ZERO, cgst = ZERO, sgst = ZERO, igst = ZERO,
        netAmt = amount(netAmt ?: "0"), payMode = if (payMode == PayMode.UPI.code) PayMode.UPI else PayMode.CASH,
        printed = billPrinted != "N", shift = shiftNameOut.orEmpty(),
        advAmt = amount(billAdvAmt ?: "0"),
        balAmt = amount(balAmt ?: netAmt ?: "0"),
    )

    private fun SyncExceptionDto.toRow(): SyncExceptionRow {
        fun field(name: String) = payload?.get(name)?.jsonPrimitive?.contentOrNull
        val bill = tableName == BILL_TABLE
        return SyncExceptionRow(
            id = id, transId = transId, isBill = bill,
            ref = (if (bill) field("bill_no") else field("receipt_no")).orEmpty(),
            reason = reason, userName = userName.orEmpty(), createdAt = DateTimes.fromServerText(createdAt), resolved = resolvedFlag == "Y",
        )
    }

    private companion object {
        /** Dashboard / bags in store refresh while open. */
        const val REFRESH_MS = 30_000L
        const val REPORT_PASSWORD_REQUIRED = "REPORT_PASSWORD_REQUIRED"
        const val BILL_TABLE = "td_receipt"
        val ZERO: BigDecimal = BigDecimal.ZERO.money()
    }
}

/**
 * The app's ReportRepository: the station admin (online only, logged in to the server) gets the
 * server's reports; counter users keep their own data on the phone (offline work).
 */
@Singleton
class ReportRepositoryRouter @Inject constructor(
    private val local: ReportRepositoryImpl,
    private val remoteFactory: RemoteReportRepositoryFactory,
    private val sessionProvider: SessionProvider,
) : ReportRepository {

    private fun serverAdmin(s: UserSession?) = s != null && s.userType == UserTypes.ADMIN && s.customerId != null

    private fun pick(): ReportRepository = if (serverAdmin(sessionProvider.current)) remoteFactory.own else local

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDashboard(): Flow<DashboardData> =
        sessionProvider.state.map { serverAdmin((it as? SessionState.LoggedIn)?.session) }
            .distinctUntilChanged()
            .flatMapLatest { online -> if (online) remoteFactory.own.observeDashboard() else local.observeDashboard() }

    override suspend fun reportAccess() = pick().reportAccess()
    override suspend fun verifyReportPassword(password: String) = pick().verifyReportPassword(password)
    override suspend fun reportUsers() = pick().reportUsers()
    override suspend fun reportShifts() = pick().reportShifts()
    override suspend fun report(filter: ReportFilter) = pick().report(filter)
    override suspend fun reportDocument(filter: ReportFilter) = pick().reportDocument(filter)
    override suspend fun exceptions() = pick().exceptions()
    override suspend fun resolveException(id: Long) = pick().resolveException(id)
}
