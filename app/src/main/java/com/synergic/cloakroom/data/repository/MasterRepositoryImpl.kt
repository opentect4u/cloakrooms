package com.synergic.cloakroom.data.repository

import androidx.room.withTransaction
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.entity.HeaderFooterEntity
import com.synergic.cloakroom.data.db.entity.IdProofEntity
import com.synergic.cloakroom.data.db.entity.ShiftEntity
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.repository.CustomerProfile
import com.synergic.cloakroom.domain.repository.HeaderFooterItem
import com.synergic.cloakroom.domain.repository.IdProofItem
import com.synergic.cloakroom.domain.repository.InvalidInputException
import com.synergic.cloakroom.domain.repository.LineType
import com.synergic.cloakroom.domain.repository.MasterRepository
import com.synergic.cloakroom.domain.repository.ShiftItem
import com.synergic.cloakroom.domain.validation.Gstin
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MasterRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val sessionProvider: SessionProvider,
    private val time: TimeSource,
) : MasterRepository {

    // ---- Cloak room details (md_customer) --------------------------------------------------

    override fun observeCustomer(): Flow<CustomerProfile?> =
        db.customerDao().observe(Tenant.CUSTOMER_ID).map { c ->
            c?.let {
                CustomerProfile(
                    name = it.customerName,
                    address = it.customerAddr,
                    pinCode = it.pinCode,
                    gstNo = it.gstNo.orEmpty(),
                    upiId = it.upiId.orEmpty(),
                    mobile = it.mobileNo,
                    email = it.email.orEmpty(),
                    renewalDt = it.renewalDt.orEmpty(),
                    userLimit = it.userLimit,
                )
            }
        }

    override suspend fun updateCustomer(name: String, address: String, pinCode: String, gstNo: String, upiId: String) {
        val admin = sessionProvider.requireAdmin()
        val cleanName = name.trim().replace(Regex("\\s+"), " ")
        val cleanAddr = address.trim()
        val pin = pinCode.filter { !it.isWhitespace() }
        val gst = gstNo.trim().uppercase()
        val upi = upiId.trim()
        when {
            cleanName.isEmpty() -> throw InvalidInputException("Enter the cloak room name.")
            cleanName.length > MAX_CUSTOMER_NAME -> throw InvalidInputException("The name is too long (max $MAX_CUSTOMER_NAME).")
            cleanAddr.isEmpty() -> throw InvalidInputException("Enter the address.")
            cleanAddr.length > MAX_ADDRESS -> throw InvalidInputException("The address is too long (max $MAX_ADDRESS).")
            !PIN_PATTERN.matches(pin) -> throw InvalidInputException("PIN code must be 6 digits, e.g. 711101.")
            gst.isNotEmpty() && !Gstin.isValid(gst) -> throw InvalidInputException(Gstin.FORMAT_MESSAGE)
            upi.isNotEmpty() && !UPI_PATTERN.matches(upi) -> throw InvalidInputException("UPI ID must look like name@bank.")
        }
        db.withTransaction {
            val row = db.customerDao().get(Tenant.CUSTOMER_ID) ?: throw InvalidInputException("Cloak room not found.")
            db.customerDao().update(
                row.copy(
                    customerName = cleanName,
                    customerAddr = cleanAddr,
                    pinCode = pin,
                    gstNo = gst.ifEmpty { null },
                    upiId = upi.ifEmpty { null },
                    syncFlag = "N",
                    localVersion = row.localVersion + 1,
                    updatedBy = admin.userId,
                    updatedAt = now(),
                )
            )
        }
    }

    // ---- ID proofs -------------------------------------------------------------------------

    override fun observeIdProofs(): Flow<List<IdProofItem>> =
        db.idProofDao().observeActive().map { list -> list.map { IdProofItem(it.idProofId, it.idProofName) } }

    override suspend fun saveIdProof(id: Long?, name: String) {
        val admin = sessionProvider.requireAdmin()
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) throw InvalidInputException("Enter the ID proof name.")
        if (clean.length > MAX_NAME) throw InvalidInputException("The name is too long (max $MAX_NAME).")
        val now = now()
        db.withTransaction {
            if (db.idProofDao().countByName(clean, id ?: 0) > 0) throw InvalidInputException("\"$clean\" already exists.")
            if (id == null) {
                db.idProofDao().insert(
                    IdProofEntity(idProofName = clean, createdBy = admin.userId, createdAt = now, updatedBy = admin.userId, updatedAt = now)
                )
            } else {
                val row = db.idProofDao().get(id)?.takeIf { it.isDeleted == "N" } ?: throw InvalidInputException("ID proof not found.")
                db.idProofDao().update(
                    row.copy(idProofName = clean, syncFlag = "N", localVersion = row.localVersion + 1, updatedBy = admin.userId, updatedAt = now)
                )
            }
        }
    }

    override suspend fun deleteIdProof(id: Long) {
        val admin = sessionProvider.requireAdmin()
        db.withTransaction {
            val row = db.idProofDao().get(id)?.takeIf { it.isDeleted == "N" } ?: return@withTransaction
            db.idProofDao().update(
                row.copy(isDeleted = "Y", syncFlag = "N", localVersion = row.localVersion + 1, updatedBy = admin.userId, updatedAt = now())
            )
        }
    }

    // ---- Header / footer / logo ------------------------------------------------------------

    override fun observeHeaderFooter(): Flow<List<HeaderFooterItem>> =
        db.headerFooterDao().observeActive(Tenant.CUSTOMER_ID).map { list -> list.map { it.toItem() } }

    override suspend fun saveHeaderFooter(item: HeaderFooterItem) {
        val admin = sessionProvider.requireAdmin()
        val text = item.text.trim()
        when (item.type) {
            LineType.LOGO -> if (item.image == null || item.image.isEmpty()) throw InvalidInputException("Choose a logo picture.")
            else -> {
                if (text.isEmpty()) throw InvalidInputException("Enter the text of the line.")
                if (text.length > MAX_LINE) throw InvalidInputException("The line is too long (max $MAX_LINE characters).")
            }
        }
        val now = now()
        db.withTransaction {
            val base = if (item.id == 0L) {
                HeaderFooterEntity(
                    customerId = Tenant.CUSTOMER_ID, lineType = item.type.code,
                    // At the end of its group (headers / footers print by line_order).
                    lineOrder = (db.headerFooterDao().getActive(Tenant.CUSTOMER_ID).filter { it.lineType == item.type.code }
                        .maxOfOrNull { it.lineOrder } ?: 0) + 1,
                    createdBy = admin.userId, createdAt = now,
                )
            } else {
                db.headerFooterDao().get(item.id)?.takeIf { it.isDeleted == "N" && it.customerId == Tenant.CUSTOMER_ID }
                    ?: throw InvalidInputException("Line not found.")
            }
            val row = base.copy(
                lineType = item.type.code,
                narration = if (item.type == LineType.LOGO) text.ifEmpty { null } else text,
                image = if (item.type == LineType.LOGO) item.image else null,
                inFlag = yn(item.onReceipt),
                outFlag = yn(item.onBill),
                reportFlag = yn(item.onReport),
                updatedBy = admin.userId,
                updatedAt = now,
            )
            if (item.id == 0L) {
                db.headerFooterDao().insert(row)
            } else {
                db.headerFooterDao().update(row.copy(syncFlag = "N", localVersion = base.localVersion + 1))
            }
        }
    }

    override suspend fun deleteHeaderFooter(id: Long) {
        val admin = sessionProvider.requireAdmin()
        db.withTransaction {
            val row = db.headerFooterDao().get(id)?.takeIf { it.isDeleted == "N" } ?: return@withTransaction
            db.headerFooterDao().update(
                row.copy(isDeleted = "Y", syncFlag = "N", localVersion = row.localVersion + 1, updatedBy = admin.userId, updatedAt = now())
            )
        }
    }

    /**
     * Lines print in id order and the table has no order column, so moving swaps the contents of
     * two rows of the same type (both rows are marked for sync).
     */
    override suspend fun moveHeaderFooter(id: Long, up: Boolean) {
        val admin = sessionProvider.requireAdmin()
        db.withTransaction {
            val all = db.headerFooterDao().getActive(Tenant.CUSTOMER_ID)
            val me = all.firstOrNull { it.id == id } ?: return@withTransaction
            val sameType = all.filter { it.lineType == me.lineType }
            val index = sameType.indexOfFirst { it.id == id }
            val other = sameType.getOrNull(if (up) index - 1 else index + 1) ?: return@withTransaction
            val now = now()
            fun HeaderFooterEntity.takeContentOf(src: HeaderFooterEntity) = copy(
                narration = src.narration, image = src.image, inFlag = src.inFlag, outFlag = src.outFlag,
                reportFlag = src.reportFlag, syncFlag = "N", localVersion = localVersion + 1,
                updatedBy = admin.userId, updatedAt = now,
            )
            db.headerFooterDao().update(me.takeContentOf(other))
            db.headerFooterDao().update(other.takeContentOf(me))
        }
    }

    // ---- Shifts ----------------------------------------------------------------------------

    override fun observeShifts(): Flow<List<ShiftItem>> =
        db.shiftDao().observeActive(Tenant.CUSTOMER_ID).map { list -> list.map { ShiftItem(it.shiftId, it.shiftName, it.fromTime, it.toTime) } }

    override suspend fun saveShift(id: Long?, name: String, fromTime: String, toTime: String) {
        val admin = sessionProvider.requireAdmin()
        val clean = name.trim()
        if (clean.isEmpty()) throw InvalidInputException("Enter the shift name.")
        if (clean.length > MAX_NAME) throw InvalidInputException("The name is too long (max $MAX_NAME).")
        val from = normaliseTime(fromTime) ?: throw InvalidInputException("From time must be like 06:00 (24-hour clock).")
        val to = normaliseTime(toTime) ?: throw InvalidInputException("To time must be like 14:00 (24-hour clock).")
        val now = now()
        db.withTransaction {
            if (id == null) {
                db.shiftDao().insert(
                    ShiftEntity(
                        customerId = Tenant.CUSTOMER_ID, shiftName = clean, fromTime = from, toTime = to,
                        createdBy = admin.userId, createdAt = now, updatedBy = admin.userId, updatedAt = now,
                    )
                )
            } else {
                val row = db.shiftDao().get(id)?.takeIf { it.isDeleted == "N" } ?: throw InvalidInputException("Shift not found.")
                db.shiftDao().update(
                    row.copy(
                        shiftName = clean, fromTime = from, toTime = to,
                        syncFlag = "N", localVersion = row.localVersion + 1, updatedBy = admin.userId, updatedAt = now,
                    )
                )
            }
        }
    }

    override suspend fun deleteShift(id: Long) {
        val admin = sessionProvider.requireAdmin()
        db.withTransaction {
            val row = db.shiftDao().get(id)?.takeIf { it.isDeleted == "N" } ?: return@withTransaction
            db.shiftDao().update(
                row.copy(isDeleted = "Y", syncFlag = "N", localVersion = row.localVersion + 1, updatedBy = admin.userId, updatedAt = now())
            )
        }
    }

    private fun now() = DateTimes.toDbText(time.now())

    private fun yn(b: Boolean) = if (b) "Y" else "N"

    private fun HeaderFooterEntity.toItem() = HeaderFooterItem(
        id = id,
        type = LineType.entries.firstOrNull { it.code == lineType } ?: LineType.HEADER,
        text = narration.orEmpty(),
        image = image,
        onReceipt = inFlag == "Y",
        onBill = outFlag == "Y",
        onReport = reportFlag == "Y",
    )

    companion object {
        private const val MAX_NAME = 50
        private const val MAX_CUSTOMER_NAME = 100
        private const val MAX_ADDRESS = 200
        /** Indian PIN code: 6 digits, not starting with 0. */
        private val PIN_PATTERN = Regex("^[1-9][0-9]{5}$")
        /** UPI VPA: handle@provider. */
        private val UPI_PATTERN = Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9]{1,63}$")
        /** A line wider than the 80 mm paper still wraps; this only stops pasted paragraphs. */
        private const val MAX_LINE = 100

        /** "6:00" → "06:00"; null when not a valid 24-hour time. */
        fun normaliseTime(text: String): String? {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text.trim()) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) "%02d:%02d".format(h, min) else null
        }
    }
}
