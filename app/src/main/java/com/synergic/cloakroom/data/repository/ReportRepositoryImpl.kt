package com.synergic.cloakroom.data.repository

import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.dao.BillWithBag
import com.synergic.cloakroom.data.db.entity.LuggageInOutEntity
import com.synergic.cloakroom.data.security.PasswordHasher
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.billing.ZERO_MONEY
import com.synergic.cloakroom.domain.billing.money
import com.synergic.cloakroom.domain.billing.toMoney
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.print.ReportFormatter
import com.synergic.cloakroom.domain.report.BillRow
import com.synergic.cloakroom.domain.report.CheckinRow
import com.synergic.cloakroom.domain.report.DashboardData
import com.synergic.cloakroom.domain.report.ReportAccess
import com.synergic.cloakroom.domain.report.ReportData
import com.synergic.cloakroom.domain.report.ReportFilter
import com.synergic.cloakroom.domain.report.ReportShift
import com.synergic.cloakroom.domain.report.ReportUser
import com.synergic.cloakroom.domain.repository.ReportRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReportRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val sessionProvider: SessionProvider,
    private val settingsRepository: SettingsRepository,
    private val hasher: PasswordHasher,
    private val time: TimeSource,
) : ReportRepository {

    /** The session (user id + start time) in which the report password was entered. */
    @Volatile private var unlockedSession: Pair<Long, String>? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDashboard(): Flow<DashboardData> =
        combine(sessionProvider.state, settingsRepository.currentSettings, todayFlow()) { state, settings, today ->
            Triple((state as? SessionState.LoggedIn)?.session, settings, today)
        }.distinctUntilChanged().flatMapLatest { (session, settings, today) ->
            if (session == null || settings == null) return@flatMapLatest emptyFlow()
            // Bags in store are counted for everyone (they are physically in the room);
            // today's numbers are the user's own unless the user is an admin.
            val scope = if (session.isAdmin) null else session.id
            combine(
                db.luggageDao().observeInStore(Tenant.CUSTOMER_ID),
                db.luggageDao().observeCheckinCount(Tenant.CUSTOMER_ID, today, scope),
                db.receiptDao().observeBillsOn(Tenant.CUSTOMER_ID, today, scope),
            ) { inStore, checkins, bills ->
                DashboardData(
                    receiptsInStore = inStore.size,
                    bagsInStore = inStore.sumOf { it.luggageQty },
                    todayCheckins = checkins,
                    todayBills = bills.size,
                    todayCollection = if (settings.totalCollectionFlag) {
                        bills.fold(ZERO_MONEY) { acc, b -> acc + b.netAmt.toMoney() }.money()
                    } else {
                        null
                    },
                    allUsers = session.isAdmin,
                )
            }
        }

    override suspend fun reportAccess(): ReportAccess {
        val session = sessionProvider.requireLogin()
        val settings = settingsRepository.getSettings(session.id)
        return when {
            session.isAdmin -> ReportAccess.Allowed(needsPassword = settings.reportPwdFlag, canChooseUser = true)
            settings.reportFlag -> ReportAccess.Allowed(needsPassword = settings.reportPwdFlag, canChooseUser = false)
            else -> ReportAccess.Denied
        }
    }

    override suspend fun verifyReportPassword(password: String): Boolean {
        val session = sessionProvider.requireLogin()
        val settings = settingsRepository.getSettings(session.id)
        val ok = !settings.reportPwdFlag || hasher.verify(password, settings.reportPwdHash)
        if (ok) unlockedSession = session.id to session.sessionStartedAt
        return ok
    }

    override suspend fun reportUsers(): List<ReportUser> {
        val admin = sessionProvider.requireAdmin()
        val settings = settingsRepository.getSettings(admin.id)
        // Users who may do counter work: counter users only (admins never check in / out).
        return db.userDao().getAll(Tenant.CUSTOMER_ID)
            .filter { settings.canWorkCounter(it.userType) }
            .map { ReportUser(it.id, it.userCode.orEmpty(), it.userName) }
    }

    override suspend fun reportShifts(): List<ReportShift> {
        sessionProvider.requireLogin()
        return db.shiftDao().getAllIncludingRemoved(Tenant.CUSTOMER_ID)
            .map { ReportShift(it.shiftId, it.shiftName, it.fromTime, it.toTime, removed = it.isDeleted == "Y") }
    }

    override suspend fun report(filter: ReportFilter): ReportData {
        val session = sessionProvider.requireLogin()
        val settings = settingsRepository.getSettings(session.id)
        val f = checkFilter(filter, session, settings)
        val from = f.from.toString()
        val to = f.to.toString()
        val codes = db.userDao().getAll(Tenant.CUSTOMER_ID).associate { it.id to it.userCode.orEmpty() }
        val shifts = db.shiftDao().getAllIncludingRemoved(Tenant.CUSTOMER_ID).associate { it.shiftId to it.shiftName }
        val checkins = db.luggageDao().getCheckins(Tenant.CUSTOMER_ID, from, to, f.userId, f.shiftId).map { it.toRow(codes, shifts) }
        val bills = db.receiptDao().getBillsWithBag(Tenant.CUSTOMER_ID, from, to, f.userId, f.shiftId).map { it.toRow(codes, shifts) }
        return ReportData(f, checkins, bills)
    }

    override suspend fun reportDocument(filter: ReportFilter): PrintDocument = formatDocument(report(filter))

    /** [data] laid out for printing / PDF with this phone's cloak room name, lines and paper width. */
    suspend fun formatDocument(data: ReportData): PrintDocument {
        val customer = db.customerDao().get(Tenant.CUSTOMER_ID)
        val all = db.headerFooterDao().getActive(Tenant.CUSTOMER_ID).filter { it.reportFlag == "Y" }
        val lines = all.filter { it.lineType != "L" && !it.narration.isNullOrBlank() }
        val userLabel = data.filter.userId?.let { id -> db.userDao().getById(id)?.let { "${it.userCode} ${it.userName}" } } ?: "All users"
        val shiftLabel = data.filter.shiftId?.let { id -> reportShifts().firstOrNull { it.id == id }?.label } ?: "All shifts"
        return formatDocument(
            data, ReportHeading(
                customerName = customer?.customerName.orEmpty(),
                headerLines = lines.filter { it.lineType == "H" }.map { it.narration!! },
                footerLines = lines.filter { it.lineType == "F" }.map { it.narration!! },
                logo = all.firstOrNull { it.lineType == "L" && it.image != null }?.image,
                userLabel = userLabel,
                shiftLabel = shiftLabel,
            ),
        )
    }

    /** [data] laid out with [heading] (e.g. another customer's, for the super admin) and this phone's paper width. */
    suspend fun formatDocument(data: ReportData, heading: ReportHeading): PrintDocument {
        val session = sessionProvider.requireLogin()
        return ReportFormatter.format(
            data = data,
            customerName = heading.customerName,
            headerLines = heading.headerLines,
            footerLines = heading.footerLines,
            userLabel = heading.userLabel,
            shiftLabel = heading.shiftLabel,
            paperWidthMm = settingsRepository.getSettings(session.id).paperWidthMm,
            logo = heading.logo,
        )
    }

    /** Applies the permission rules; returns the filter to use (dates in order, user fixed for operators). */
    private fun checkFilter(filter: ReportFilter, session: UserSession, settings: AppSettings): ReportFilter {
        if (!session.isAdmin && !settings.reportFlag) throw NotAuthorizedException("Reports are switched off for counter users.")
        if (settings.reportPwdFlag && unlockedSession != (session.id to session.sessionStartedAt)) {
            throw NotAuthorizedException("Enter the report password first.")
        }
        val userId = when {
            session.isAdmin -> filter.userId
            filter.userId == null || filter.userId == session.id -> session.id
            else -> throw NotAuthorizedException("You can only see your own reports.")
        }
        val (from, to) = if (filter.from <= filter.to) filter.from to filter.to else filter.to to filter.from
        return ReportFilter(from, to, userId, filter.shiftId)
    }

    /** Today's local date, re-checked every minute so the dashboard rolls over at midnight. */
    private fun todayFlow(): Flow<String> = flow {
        while (true) {
            emit(DateTimes.localDateText(time.now()))
            delay(60_000)
        }
    }.distinctUntilChanged()

    private fun LuggageInOutEntity.toRow(codes: Map<Long, String>, shifts: Map<Long, String>) = CheckinRow(
        transId = transId,
        receiptNo = receiptNo,
        dateTimeIn = dateTimeIn,
        userCode = codes[userIdIn].orEmpty(),
        name = pasnName,
        mobile = pasnMobNo,
        pnr = pnrNo,
        bags = luggageQty,
        extraBags = extraLugageQty,
        isOut = outFlag == "Y",
        shift = shiftIdIn?.let { shifts[it] }.orEmpty(),
    )

    private fun BillWithBag.toRow(codes: Map<Long, String>, shifts: Map<Long, String>) = BillRow(
        transId = receipt.transId,
        billNo = receipt.billNo,
        billDt = receipt.billDt,
        receiptNo = receiptNo,
        dateTimeOut = dateTimeOut ?: receipt.createdAt.orEmpty(),
        userCode = codes[receipt.userId].orEmpty(),
        name = name,
        bags = receipt.luggageQty,
        extraBags = extraBags,
        baseAmt = receipt.baseAmt.toMoney(),
        penaltyAmt = receipt.penalAmt.toMoney(),
        taxableAmt = receipt.taxableAmt.toMoney(),
        cgst = receipt.cgst.toMoney(),
        sgst = receipt.sgst.toMoney(),
        igst = receipt.igst.toMoney(),
        netAmt = receipt.netAmt.toMoney(),
        payMode = if (receipt.payMode == PayMode.UPI.code) PayMode.UPI else PayMode.CASH,
        printed = receipt.billPrinted == "Y",
        shift = receipt.shiftId?.let { shifts[it] }.orEmpty(),
    )
}

/** Name, printed lines and filter labels at the top of a printed report. */
data class ReportHeading(
    val customerName: String,
    val headerLines: List<String>,
    val footerLines: List<String>,
    val logo: ByteArray?,
    val userLabel: String,
    val shiftLabel: String,
)
