package com.synergic.cloakroom.data.sync

import android.util.Base64
import androidx.room.withTransaction
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.entity.AssignShiftEntity
import com.synergic.cloakroom.data.db.entity.CustomerEntity
import com.synergic.cloakroom.data.db.entity.GstEntity
import com.synergic.cloakroom.data.db.entity.HeaderFooterEntity
import com.synergic.cloakroom.data.db.entity.IdProofEntity
import com.synergic.cloakroom.data.db.entity.RateDtlsEntity
import com.synergic.cloakroom.data.db.entity.ShiftEntity
import com.synergic.cloakroom.data.db.entity.UserCounterEntity
import com.synergic.cloakroom.data.db.entity.UserEntity
import com.synergic.cloakroom.data.remote.CountersDto
import com.synergic.cloakroom.data.remote.CustomerDto
import com.synergic.cloakroom.data.remote.HeaderFooterDto
import com.synergic.cloakroom.data.remote.MastersDto
import com.synergic.cloakroom.data.remote.ShiftDto
import com.synergic.cloakroom.domain.repository.ServerSettingKey
import com.synergic.cloakroom.domain.settings.SettingKeys
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes what the server sends at login (and later in a pull) into the phone's database, so the
 * customer works with exactly what the Super Admin / Admin set up (API_GUIDE.md §3, §6.3):
 * masters with the server's ids, the §8 settings and the receipt / bill counters.
 *
 * The server owns masters ("devices never create master rows"): with [complete] = true (login, a
 * first pull) a row the server did not send is marked removed on the phone; an incremental pull
 * only upserts (removed rows come with is_deleted = Y).
 */
@Singleton
class ServerDataImporter @Inject constructor(
    private val db: AppDatabase,
) {
    suspend fun import(
        customerId: Long,
        userDbId: Long,
        masters: MastersDto?,
        settings: Map<String, String>?,
        counters: CountersDto?,
        serverTime: String,
        complete: Boolean,
    ) = db.withTransaction {
        masters?.let { importMasters(customerId, it, serverTime, complete) }
        val rate = masters?.let { currentRate(customerId, it, serverTime) }
        // Login and pull send every key; GST_PCT only when the cloak room set its own GST %.
        // Absent = blank here, so billing uses the shared GST row of the bill date (§7).
        val gstNotSet = if (!settings.isNullOrEmpty() && SettingKeys.GST_PCT !in settings) mapOf(SettingKeys.GST_PCT to "") else emptyMap()
        importSettings(customerId, settings.orEmpty() + gstNotSet + rate.orEmpty(), serverTime)
        counters?.let { importCounters(customerId, userDbId, it, serverTime) }
    }

    /**
     * The customer as the server sent it (login, `GET /customers/me`, `PATCH /customers/me`):
     * the cloak room name, GSTIN and UPI ID printed on bills and used for the UPI QR.
     */
    suspend fun importCustomer(customer: CustomerDto, at: String) = db.withTransaction {
        val existing = db.customerDao().get(customer.customerId)
        db.customerDao().upsert(
            CustomerEntity(
                customerId = customer.customerId,
                customerName = customer.customerName,
                customerAddr = customer.customerAddr,
                pinCode = customer.pinCode,
                state = customer.state,
                mobileNo = customer.mobileNo,
                email = customer.email,
                renewalDt = customer.renewalDt,
                gstNo = customer.gstNo,
                upiId = customer.upiId,
                userLimit = customer.userLimit,
                activeFlag = customer.activeFlag,
                syncFlag = "Y",
                localVersion = existing?.localVersion ?: 1,
                createdBy = existing?.createdBy, createdAt = existing?.createdAt,
                updatedBy = Tenant.SYSTEM_USER, updatedAt = at,
            )
        )
    }

    /** The station admin changed header / footer lines on the server: [rows] is the full list now. */
    suspend fun replaceLines(customerId: Long, rows: List<HeaderFooterDto>, at: String) =
        db.withTransaction { importLines(customerId, rows, at, complete = true) }

    /** The station admin changed shifts on the server: [rows] is the full list now. */
    suspend fun replaceShifts(customerId: Long, rows: List<ShiftDto>, at: String) =
        db.withTransaction { importShifts(customerId, rows, at, complete = true) }

    private suspend fun importMasters(customerId: Long, m: MastersDto, at: String, complete: Boolean) {
        val by = Tenant.SYSTEM_USER

        // ID proof types and GST rates are global.
        val proofs = db.idProofDao().getAllIncludingRemoved().associateBy { it.idProofId }
        db.idProofDao().upsert(m.idProofs.map {
            IdProofEntity(
                idProofId = it.idProofId, idProofName = it.idProofName, maskFlag = it.maskFlag, isDeleted = it.isDeleted,
                syncFlag = "Y", localVersion = proofs[it.idProofId]?.localVersion ?: 1, updatedBy = by, updatedAt = at,
            )
        })
        if (complete) {
            val sent = m.idProofs.map { it.idProofId }.toSet()
            db.idProofDao().upsert(proofs.values.filter { it.idProofId !in sent && it.isDeleted == "N" }.map { it.removed(at) })
        }

        db.gstDao().upsert(m.gst.map {
            GstEntity(
                gstId = it.gstId, cgstPct = it.cgstPct.toDouble(), sgstPct = it.sgstPct.toDouble(), igstPct = it.igstPct.toDouble(),
                effectiveFrom = it.effectiveFrom, isDeleted = it.isDeleted, syncFlag = "Y", updatedBy = by, updatedAt = at,
            )
        })
        if (complete) {
            val sent = m.gst.map { it.gstId }.toSet()
            db.gstDao().upsert(db.gstDao().getAll().filter { it.gstId !in sent && it.isDeleted == "N" }.map {
                it.copy(isDeleted = "Y", syncFlag = "Y", updatedAt = at)
            })
        }

        // Customer masters.
        db.rateDao().upsert(m.rates.filter { it.customerId == customerId }.map {
            RateDtlsEntity(
                rateId = it.rateId, customerId = customerId, blockHours = it.blockHours,
                firstBlockRate = it.firstBlockRate.toDouble(), nextBlockRate = it.nextBlockRate.toDouble(),
                effectiveFrom = it.effectiveFrom, isDeleted = it.isDeleted, syncFlag = "Y", updatedBy = by, updatedAt = at,
            )
        })
        if (complete) {
            val sent = m.rates.map { it.rateId }.toSet()
            db.rateDao().upsert(db.rateDao().getAll(customerId).filter { it.rateId !in sent && it.isDeleted == "N" }.map {
                it.copy(isDeleted = "Y", syncFlag = "Y", updatedAt = at)
            })
        }

        importShifts(customerId, m.shifts, at, complete)

        val assignments = db.assignShiftDao().getAllIncludingRemoved(customerId).associateBy { it.id }
        db.assignShiftDao().upsert(m.shiftAssignments.filter { it.customerId == customerId }.map {
            AssignShiftEntity(
                id = it.id, customerId = customerId, userId = it.userId, shiftId = it.shiftId, effectiveFrom = it.effectiveFrom,
                isDeleted = it.isDeleted, syncFlag = "Y", localVersion = assignments[it.id]?.localVersion ?: 1, updatedBy = by, updatedAt = at,
            )
        })
        if (complete) {
            val sent = m.shiftAssignments.map { it.id }.toSet()
            db.assignShiftDao().upsert(assignments.values.filter { it.id !in sent && it.isDeleted == "N" }.map {
                it.copy(isDeleted = "Y", syncFlag = "Y", updatedAt = at)
            })
        }

        importLines(customerId, m.headerFooter, at, complete)

        importUsers(customerId, m, at, complete)
    }

    private suspend fun importShifts(customerId: Long, rows: List<ShiftDto>, at: String, complete: Boolean) {
        val by = Tenant.SYSTEM_USER
        val shifts = db.shiftDao().getAllIncludingRemoved(customerId).associateBy { it.shiftId }
        db.shiftDao().upsert(rows.filter { it.customerId == customerId }.map {
            ShiftEntity(
                shiftId = it.shiftId, customerId = customerId, shiftName = it.shiftName,
                fromTime = it.fromTime.take(5), toTime = it.toTime.take(5), isDeleted = it.isDeleted,
                syncFlag = "Y", localVersion = shifts[it.shiftId]?.localVersion ?: 1, updatedBy = by, updatedAt = at,
            )
        })
        if (complete) {
            val sent = rows.map { it.shiftId }.toSet()
            db.shiftDao().upsert(shifts.values.filter { it.shiftId !in sent && it.isDeleted == "N" }.map {
                it.copy(isDeleted = "Y", syncFlag = "Y", updatedAt = at)
            })
        }
    }

    private suspend fun importLines(customerId: Long, rows: List<HeaderFooterDto>, at: String, complete: Boolean) {
        val by = Tenant.SYSTEM_USER
        val lines = db.headerFooterDao().getAllIncludingRemoved(customerId).associateBy { it.id }
        db.headerFooterDao().upsert(rows.filter { it.customerId == customerId }.map {
            HeaderFooterEntity(
                id = it.id, customerId = customerId, lineType = it.lineType, lineOrder = it.lineOrder, narration = it.narration,
                image = it.image?.let { b64 -> runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() },
                inFlag = it.inFlag, outFlag = it.outFlag, reportFlag = it.reportFlag, isDeleted = it.isDeleted,
                syncFlag = "Y", localVersion = lines[it.id]?.localVersion ?: 1, updatedBy = by, updatedAt = at,
            )
        })
        if (complete) {
            val sent = rows.map { it.id }.toSet()
            db.headerFooterDao().upsert(lines.values.filter { it.id !in sent && it.isDeleted == "N" }.map {
                it.copy(isDeleted = "Y", syncFlag = "Y", updatedAt = at)
            })
        }
    }

    /**
     * masters.users has no login id; a row already on the phone keeps its own (the logged-in
     * user's real one), a new row gets "#<id>" until that user logs in here. Removed users stay
     * for old bags and bills (is_deleted = Y), but are not offered in pickers.
     */
    private suspend fun importUsers(customerId: Long, m: MastersDto, at: String, complete: Boolean) {
        val local = db.userDao().getAllIncludingRemoved(customerId).associateBy { it.id }
        db.userDao().upsertAll(m.users.filter { it.customerId == null || it.customerId == customerId }.map { u ->
            val row = local[u.id] ?: db.userDao().getById(u.id)
            (row ?: UserEntity(id = u.id, customerId = customerId, userId = "#${u.id}", userName = u.userName, userType = u.userType))
                .copy(
                    customerId = customerId, userName = u.userName, userType = u.userType, userCode = u.userCode,
                    allowFlag = u.allowFlag, isDeleted = u.isDeleted, syncFlag = "Y", updatedBy = Tenant.SYSTEM_USER, updatedAt = at,
                )
        })
        if (complete && m.users.isNotEmpty()) {
            val sent = m.users.map { it.id }.toSet()
            db.userDao().upsertAll(local.values.filter { it.id !in sent && it.isDeleted == "N" }.map {
                it.copy(isDeleted = "Y", syncFlag = "Y", updatedAt = at)
            })
        }
    }

    /**
     * The §8 values (customer settings merged with this user's overrides) into the customer-wide
     * rows. REPORT_PWD comes back as "********" and is not copied. Print tab settings (QR_FLAG,
     * PAPER_WIDTH) belong to the device and keep the phone's value; keys the phone has no row for
     * are skipped.
     */
    private suspend fun importSettings(customerId: Long, settings: Map<String, String>, at: String) {
        val rows = db.settingDao().getScopedRows(customerId, null).filter { it.userId == null }.associateBy { it.settingName }
        settings.forEach { (name, value) ->
            if (name == ServerSettingKey.REPORT_PWD.name) return@forEach
            val row = rows[name]?.takeIf { it.settingType != PRINT_GROUP } ?: return@forEach
            if (row.settingValue != value) {
                db.settingDao().update(row.copy(settingValue = value, syncFlag = "Y", updatedBy = Tenant.SYSTEM_USER, updatedAt = at))
            }
        }
    }

    /**
     * Billing still reads its rates from md_setting (until step e), so the rate in force today is
     * copied into the three rate settings, as the Super Admin's Billing tab shows them.
     */
    private fun currentRate(customerId: Long, m: MastersDto, serverTime: String): Map<String, String>? {
        val today = serverTime.take(10)
        val rate = m.rates
            .filter { it.customerId == customerId && it.isDeleted == "N" && it.effectiveFrom.take(10) <= today }
            .maxByOrNull { it.effectiveFrom } ?: return null
        return mapOf(
            SettingKeys.FIRST_BLOCK_RATE to rate.firstBlockRate.toBigDecimal().stripTrailingZeros().toPlainString(),
            SettingKeys.NEXT_BLOCK_RATE to rate.nextBlockRate.toBigDecimal().stripTrailingZeros().toPlainString(),
            SettingKeys.BLOCK_HOURS to rate.blockHours.toString(),
        )
    }

    /** §7: the next number is max(local, server) + 1, so the phone keeps the higher last number. */
    private suspend fun importCounters(customerId: Long, userDbId: Long, c: CountersDto, at: String) {
        listOf(RECEIPT to c.receiptLastNo, BILL to c.billLastNo).forEach { (type, serverLast) ->
            val row = db.userCounterDao().get(customerId, userDbId, type, c.periodKey)
            when {
                row == null -> if (serverLast > 0) db.userCounterDao().insert(
                    UserCounterEntity(
                        customerId = customerId, userId = userDbId, counterType = type, periodKey = c.periodKey, lastNo = serverLast,
                        syncFlag = "Y", createdBy = Tenant.SYSTEM_USER, createdAt = at, updatedBy = Tenant.SYSTEM_USER, updatedAt = at,
                    )
                )
                serverLast > row.lastNo -> db.userCounterDao().update(row.copy(lastNo = serverLast, updatedAt = at))
            }
        }
    }

    private fun IdProofEntity.removed(at: String) = copy(isDeleted = "Y", syncFlag = "Y", updatedAt = at)

    private companion object {
        /** md_user_counter.counter_type: receipts (check-in) and bills (check-out). */
        const val RECEIPT = "R"
        const val BILL = "B"
        /** md_setting.setting_type of the device-only Print tab. */
        const val PRINT_GROUP = "P"
    }
}
