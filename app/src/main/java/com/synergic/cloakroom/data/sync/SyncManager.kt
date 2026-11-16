package com.synergic.cloakroom.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.entity.LuggageInOutEntity
import com.synergic.cloakroom.data.db.entity.ReceiptEntity
import com.synergic.cloakroom.data.remote.PullLuggageDto
import com.synergic.cloakroom.data.remote.PushLuggageDto
import com.synergic.cloakroom.data.remote.PushReceiptDto
import com.synergic.cloakroom.data.remote.PushRequestDto
import com.synergic.cloakroom.data.remote.SyncApi
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.remote.Connectivity
import com.synergic.cloakroom.domain.remote.ServerException
import com.synergic.cloakroom.domain.repository.SyncOutcome
import com.synergic.cloakroom.domain.repository.SyncRepository
import com.synergic.cloakroom.domain.repository.SyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import javax.inject.Singleton

/** Where the next pull starts: the `server_time` of the last completed pull, per customer and user. */
interface SyncCursorStore {
    fun since(key: String): String?
    fun setSince(key: String, serverTime: String?)
}

@Singleton
class PrefsSyncCursorStore @Inject constructor(@ApplicationContext context: Context) : SyncCursorStore {
    private val prefs = context.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
    override fun since(key: String): String? = prefs.getString(key, null)
    override fun setSince(key: String, serverTime: String?) {
        prefs.edit().apply { if (serverTime == null) remove(key) else putString(key, serverTime) }.apply()
    }
}

/**
 * Push / pull (API_GUIDE.md §6) for the logged-in counter user or station admin.
 *
 * Push: rows with `sync_flag = N`, in batches of [BATCH]; a bill goes only together with or after
 * its luggage row (else the server would record BILL_WITHOUT_LUGGAGE). Each result, ACCEPTED or
 * EXCEPTION, sets `sync_flag = Y` only when the row's local_version is still the one pushed, so a
 * change made meanwhile is pushed again. Pull: pages until `has_more` is false, then stores
 * `server_time` for the next one.
 */
@Singleton
class SyncManager @Inject constructor(
    private val db: AppDatabase,
    private val api: SyncApi,
    private val importer: ServerDataImporter,
    private val sessionProvider: SessionProvider,
    private val connectivity: Connectivity,
    private val cursors: SyncCursorStore,
) : SyncRepository {

    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    override val pendingCount: Flow<Int>
        get() = combine(
            db.luggageDao().observePendingCount(Tenant.CUSTOMER_ID),
            db.receiptDao().observePendingCount(Tenant.CUSTOMER_ID),
        ) { a, b -> a + b }

    /** Counter users and station admins of a customer; the super admin has nothing to sync. */
    private fun counterSession(): UserSession? = sessionProvider.current?.takeIf { !it.isSuperAdmin && it.customerId != null }

    override suspend fun syncNow(): SyncOutcome = run { session ->
        val pushed = push(session)
        pull(session)
        refreshCustomer()
        SyncOutcome.Done(pushed, pendingRows())
    }

    override suspend fun pushPending(): Int {
        run { session -> push(session); SyncOutcome.Done(0, 0) }
        return pendingRows()
    }

    private suspend fun run(block: suspend (UserSession) -> SyncOutcome): SyncOutcome {
        val session = counterSession() ?: return SyncOutcome.NotLoggedIn
        if (!connectivity.isOnline()) return SyncOutcome.Offline
        return mutex.withLock {
            _status.update { it.copy(running = true) }
            try {
                block(session).also { _status.update { s -> s.copy(running = false, lastSyncAt = lastServerTime, lastError = null) } }
            } catch (e: ServerException) {
                val message = if (e.code == ServerException.NETWORK) "Cannot reach the server." else e.message ?: "Sync failed."
                _status.update { it.copy(running = false, lastError = message) }
                SyncOutcome.Failed(message)
            }
        }
    }

    private var lastServerTime: String? = null

    private suspend fun pendingRows(): Int {
        val c = Tenant.CUSTOMER_ID
        return db.luggageDao().getPending(c, Int.MAX_VALUE).size + db.receiptDao().getPending(c, Int.MAX_VALUE).size
    }

    // ---- Push ---------------------------------------------------------------------------------

    private suspend fun push(session: UserSession): Int {
        val customerId = Tenant.CUSTOMER_ID
        var pushed = 0
        while (true) {
            val luggage = db.luggageDao().getPending(customerId, BATCH)
            val inBatch = luggage.map { it.transId }.toSet()
            // A bill waits while its luggage row is pending but not in this batch.
            val receipts = db.receiptDao().getPending(customerId, BATCH).filter { r ->
                r.transId in inBatch || db.luggageDao().get(r.transId)?.syncFlag != "N"
            }
            if (luggage.isEmpty() && receipts.isEmpty()) break

            val response = api.push(PushRequestDto(luggage.map { it.toPush() }, receipts.map { it.toPush() }))
            lastServerTime = response.serverTime
            var marked = 0
            db.withTransaction {
                response.results.forEach { r ->
                    marked += when (r.table) {
                        LUGGAGE_TABLE -> db.luggageDao().markSynced(r.transId, r.localVersion)
                        RECEIPT_TABLE -> db.receiptDao().markSynced(r.transId, r.localVersion)
                        else -> 0
                    }
                }
            }
            response.counters?.let { importer.import(customerId, session.id, null, null, it, response.serverTime, complete = false) }
            pushed += marked
            // Nothing could be marked (rows changed meanwhile, or the server answered nothing): next sync retries.
            if (marked == 0) break
        }
        return pushed
    }

    private fun LuggageInOutEntity.toPush() = PushLuggageDto(
        transId = transId, localVersion = localVersion, receiptNo = receiptNo, receiptDt = receiptDt,
        userIdIn = userIdIn, deviceIdIn = deviceIdIn, shiftIdIn = shiftIdIn, dateTimeIn = dateTimeIn,
        pasnName = pasnName, pasnMobNo = pasnMobNo, pnrNo = pnrNo, luggageQty = luggageQty, extraLugageQty = extraLugageQty,
        idProofId = idProofId.takeIf { !idNumber.isNullOrEmpty() }, idNumber = idNumber?.takeIf { idProofId != null && it.isNotEmpty() },
        userIdOut = userIdOut, deviceIdOut = deviceIdOut, shiftIdOut = shiftIdOut, dateTimeOut = dateTimeOut, outFlag = outFlag,
    )

    private fun ReceiptEntity.toPush() = PushReceiptDto(
        transId = transId, localVersion = localVersion, billNo = billNo, billDt = billDt, userId = userId, deviceId = deviceId,
        shiftId = shiftId, luggageQty = luggageQty, daysCharged = daysCharged, blockHours = blockHours,
        firstBlockRate = money(firstBlockRate), nextBlockRate = money(nextBlockRate), baseAmt = money(baseAmt),
        penalAmt = money(penalAmt), netAmt = money(netAmt), taxableAmt = money(taxableAmt), gstPct = money(gstPct),
        cgst = money(cgst), sgst = money(sgst), igst = money(igst), gstFlag = gstFlag,
        graceMinutes = graceMinutes, gstInclusive = gstInclusive, penaltyGstFlag = penaltyGstFlag, payMode = payMode,
        upiRef = upiRef?.takeIf { it.isNotBlank() }, billPrinted = billPrinted,
    )

    /** Stored as REAL (schema); sent as the 2-decimal string the API expects. */
    private fun money(value: Double): String = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString()

    /**
     * The cloak room row (GSTIN, UPI ID, name…) is not in pull, so it is fetched after each pull:
     * a change by the Admin reaches every phone at its next sync. A failure here does not fail
     * the sync; the next one tries again.
     */
    private suspend fun refreshCustomer() {
        val customer = try { api.ownCustomer() } catch (e: ServerException) { return }
        if (customer.customerId == Tenant.CUSTOMER_ID) importer.importCustomer(customer, lastServerTime ?: return)
    }

    // ---- Pull ---------------------------------------------------------------------------------

    private suspend fun pull(session: UserSession) {
        val customerId = Tenant.CUSTOMER_ID
        val key = cursorKey(session)
        val since = cursors.since(key)
        var cursor: String? = null
        do {
            val page = api.pull(if (cursor == null) since else null, cursor)
            lastServerTime = page.serverTime
            importer.import(customerId, session.id, page.masters, page.settings, page.counters, page.serverTime, complete = since == null && cursor == null)
            mergeLuggage(customerId, page.luggage, page.serverTime)
            cursor = page.nextCursor.takeIf { page.hasMore }
            if (!page.hasMore) cursors.setSince(key, page.serverTime)
        } while (cursor != null)
    }

    /**
     * Upsert by trans_id (§6.3). A new bag is stored as synced. On a known bag the server's
     * check-out fills an open one; the four check-in fields a QR row lacks are filled once; a
     * removed bag is removed here. Local changes not yet pushed stay (the server merges them).
     */
    private suspend fun mergeLuggage(customerId: Long, rows: List<PullLuggageDto>, at: String) = db.withTransaction {
        val dao = db.luggageDao()
        rows.forEach { r ->
            val local = dao.get(r.transId)
            if (local == null) {
                dao.insert(
                    LuggageInOutEntity(
                        transId = r.transId, customerId = customerId, receiptNo = r.receiptNo, receiptDt = r.receiptDt,
                        userIdIn = r.userIdIn, deviceIdIn = r.deviceIdIn, shiftIdIn = r.shiftIdIn, dateTimeIn = r.dateTimeIn,
                        pasnName = r.pasnName, pasnMobNo = r.pasnMobNo, pnrNo = r.pnrNo, luggageQty = r.luggageQty,
                        extraLugageQty = r.extraLugageQty,
                        idProofId = r.idProofId, idNumber = r.idNumber, userIdOut = r.userIdOut, deviceIdOut = r.deviceIdOut,
                        shiftIdOut = r.shiftIdOut, dateTimeOut = r.dateTimeOut, outFlag = r.outFlag, isDeleted = r.isDeleted,
                        syncFlag = "Y", createdBy = r.createdBy, createdAt = r.createdAt, updatedBy = SYNC_USER, updatedAt = at,
                    )
                )
                return@forEach
            }
            if (local.customerId != customerId) return@forEach
            val takeOut = r.outFlag == "Y" && local.outFlag == "N"
            val merged = local.copy(
                deviceIdIn = local.deviceIdIn ?: r.deviceIdIn,
                shiftIdIn = local.shiftIdIn ?: r.shiftIdIn,
                // Check-in value, filled once (e.g. a row copied from an older QR without it).
                extraLugageQty = if (local.extraLugageQty == 0) r.extraLugageQty else local.extraLugageQty,
                idProofId = if (local.idProofId == null && local.idNumber == null) r.idProofId else local.idProofId,
                idNumber = if (local.idProofId == null && local.idNumber == null) r.idNumber else local.idNumber,
                userIdOut = if (takeOut) r.userIdOut else local.userIdOut,
                deviceIdOut = if (takeOut) r.deviceIdOut else local.deviceIdOut,
                shiftIdOut = if (takeOut) r.shiftIdOut else local.shiftIdOut,
                dateTimeOut = if (takeOut) r.dateTimeOut else local.dateTimeOut,
                outFlag = if (takeOut) "Y" else local.outFlag,
                isDeleted = if (r.isDeleted == "Y") "Y" else local.isDeleted,
            )
            if (merged != local) {
                dao.update(merged.copy(updatedBy = SYNC_USER, updatedAt = at))
                // The server has this state; a row with local changes still waiting keeps N.
                if (local.syncFlag == "Y") dao.setSynced(local.transId)
            }
        }
    }

    private fun cursorKey(session: UserSession) = "since_${Tenant.CUSTOMER_ID}_${session.id}"

    /** After a login the next pull starts from scratch (all open bags of the customer). */
    fun resetCursor(session: UserSession) = cursors.setSince(cursorKey(session), null)

    private companion object {
        /** §6.2: "batches of ≤ 500 rows per request". */
        const val BATCH = 500
        const val LUGGAGE_TABLE = "td_luggage_in_out"
        const val RECEIPT_TABLE = "td_receipt"
        const val SYNC_USER = "SYNC"
    }
}
