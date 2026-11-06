package com.synergic.cloakroom.data.repository

import android.util.Base64
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.remote.HeaderFooterDto
import com.synergic.cloakroom.data.remote.MastersApi
import com.synergic.cloakroom.data.remote.SuperAdminApi
import com.synergic.cloakroom.data.sync.ServerDataImporter
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.repository.CustomerProfile
import com.synergic.cloakroom.domain.repository.HeaderFooterItem
import com.synergic.cloakroom.domain.repository.IdProofItem
import com.synergic.cloakroom.domain.repository.InvalidInputException
import com.synergic.cloakroom.domain.repository.LineType
import com.synergic.cloakroom.domain.repository.MasterRepository
import com.synergic.cloakroom.domain.repository.ShiftItem
import com.synergic.cloakroom.domain.time.DateTimes
import com.synergic.cloakroom.domain.time.TimeSource
import com.synergic.cloakroom.domain.validation.Gstin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Builds a [RemoteMasterRepository] for the customer a super admin opened, or the admin's own. */
@Singleton
class RemoteMasterRepositoryFactory @Inject constructor(
    private val api: MastersApi,
    private val superAdminApi: SuperAdminApi,
    private val sessionProvider: SessionProvider,
    private val db: AppDatabase,
    private val importer: ServerDataImporter,
    private val time: TimeSource,
) {
    fun create(customerId: Long) = RemoteMasterRepository(api, superAdminApi, sessionProvider, customerId, db, importer, time)
}

/**
 * The Masters screen for one customer, on the server (API_GUIDE.md §5): cloak room details
 * (`PATCH /customers/:id`), ID proof types (global `/id-proofs`), header / footer lines and logo,
 * and shifts (`?customer_id=` / body `customer_id`). Lists are loaded by [refresh] and reloaded
 * after every change, so the screen behaves like the offline one.
 *
 * The station admin uses it for their own customer: lines and shifts are theirs to change (and
 * are copied into this phone at once, for printing and the shift check), and so are the GSTIN and
 * UPI ID (`PATCH /customers/me`). The other cloak room details and the ID proof types are set by
 * the super admin and only shown (the API allows those to S only).
 */
class RemoteMasterRepository(
    private val api: MastersApi,
    private val superAdminApi: SuperAdminApi,
    private val sessionProvider: SessionProvider,
    private val customerId: Long,
    private val db: AppDatabase,
    private val importer: ServerDataImporter,
    private val time: TimeSource,
) : MasterRepository {

    /** True for the station admin (their own customer); false for the super admin. */
    val ownCustomer: Boolean = sessionProvider.current?.isSuperAdmin != true

    private val customer = MutableStateFlow<CustomerProfile?>(null)
    private val proofs = MutableStateFlow<List<IdProofItem>?>(null)
    private val lines = MutableStateFlow<List<HeaderFooterItem>?>(null)
    private val shifts = MutableStateFlow<List<ShiftItem>?>(null)

    /** Rows as last loaded, for line_order (not part of [HeaderFooterItem]). */
    private var lineRows: List<HeaderFooterDto> = emptyList()

    private fun requireSuperAdmin() {
        if (!sessionProvider.requireLogin().isSuperAdmin) {
            throw NotAuthorizedException("Only Synergic (super admin) can change this. Please contact Synergic support.")
        }
    }

    /** Lines and shifts: the super admin, or the admin of this customer. */
    private fun requireEditor() {
        val s = sessionProvider.requireLogin()
        if (!s.isSuperAdmin && !(s.isAdmin && s.customerId == customerId)) throw NotAuthorizedException("Only an admin can do this.")
    }

    suspend fun refresh() {
        requireEditor()
        loadCustomer(); loadProofs(); loadLines(); loadShifts()
    }

    private suspend fun loadCustomer() {
        if (ownCustomer) {
            // GET /customers/:id is for the super admin; the login copied the admin's customer here.
            customer.value = db.customerDao().get(customerId)?.let {
                CustomerProfile(
                    name = it.customerName, address = it.customerAddr, pinCode = it.pinCode, gstNo = it.gstNo.orEmpty(),
                    upiId = it.upiId.orEmpty(), mobile = it.mobileNo, email = it.email.orEmpty(), renewalDt = it.renewalDt.orEmpty(),
                    userLimit = it.userLimit,
                )
            }
            return
        }
        val c = superAdminApi.customerSummary(customerId).customer
        customer.value = CustomerProfile(
            name = c.customerName, address = c.customerAddr, pinCode = c.pinCode, gstNo = c.gstNo.orEmpty(),
            upiId = c.upiId.orEmpty(), mobile = c.mobileNo, email = c.email.orEmpty(), renewalDt = c.renewalDt.orEmpty(),
            userLimit = c.userLimit,
        )
    }

    private suspend fun loadProofs() {
        proofs.value = api.listIdProofs().map { IdProofItem(it.idProofId, it.idProofName) }
    }

    private suspend fun loadLines() {
        lineRows = api.listHeaderFooter(customerId).sortedWith(compareBy({ TYPE_ORDER.indexOf(it.lineType) }, { it.lineOrder }, { it.id }))
        lines.value = lineRows.map { it.toItem() }
        if (ownCustomer) importer.replaceLines(customerId, lineRows, now())
    }

    private suspend fun loadShifts() {
        val rows = api.listShifts(customerId)
        shifts.value = rows.map { ShiftItem(it.shiftId, it.shiftName, it.fromTime.take(5), it.toTime.take(5)) }
        if (ownCustomer) importer.replaceShifts(customerId, rows, now())
    }

    private fun now() = DateTimes.toDbText(time.now())

    // ---- Cloak room ---------------------------------------------------------------------------

    override fun observeCustomer(): Flow<CustomerProfile?> = customer

    override suspend fun updateCustomer(name: String, address: String, pinCode: String, gstNo: String, upiId: String) {
        if (ownCustomer) return updateOwnPaymentDetails(gstNo, upiId)
        requireSuperAdmin()
        val gst = gstNo.trim().uppercase()
        when {
            name.isBlank() -> throw InvalidInputException("Enter the cloak room name.")
            address.isBlank() -> throw InvalidInputException("Enter the address.")
            !Regex("^\\d{6}$").matches(pinCode.trim()) -> throw InvalidInputException("PIN code must be 6 digits.")
            gst.isNotEmpty() && !Gstin.isValid(gst) -> throw InvalidInputException(Gstin.FORMAT_MESSAGE)
        }
        superAdminApi.updateCustomer(customerId, buildJsonObject {
            put("customer_name", name.trim()); put("customer_addr", address.trim()); put("pin_code", pinCode.trim())
            if (gst.isNotEmpty()) put("gst_no", gst)
            if (upiId.isNotBlank()) put("upi_id", upiId.trim())
        })
        loadCustomer()
    }

    /**
     * Station admin: GSTIN and UPI ID via `PATCH /customers/me`, sending only what changed (the
     * API wants a partial body). Name, address and PIN stay with the super admin on this screen.
     * The server's reply becomes this phone's md_customer row, so the next bill and UPI QR use it.
     */
    private suspend fun updateOwnPaymentDetails(gstNo: String, upiId: String) {
        requireEditor()
        val current = customer.value
        val gst = gstNo.trim().uppercase()
        val upi = upiId.trim()
        val fields = buildMap {
            if (gst != current?.gstNo.orEmpty()) {
                if (gst.isEmpty()) throw InvalidInputException("The GSTIN cannot be removed here. Please contact Synergic support.")
                if (!Gstin.isValid(gst)) throw InvalidInputException(Gstin.FORMAT_MESSAGE)
                put("gst_no", gst)
            }
            if (upi != current?.upiId.orEmpty()) {
                if (upi.isEmpty()) throw InvalidInputException("The UPI ID cannot be removed here. Please contact Synergic support.")
                if (!UPI_ID.matches(upi)) throw InvalidInputException("Enter the UPI ID as name@bank, e.g. cloakroom@oksbi.")
                put("upi_id", upi)
            }
        }
        if (fields.isEmpty()) return
        importer.importCustomer(api.updateOwnCustomer(fields), now())
        loadCustomer()
    }

    // ---- ID proof types (shared by all customers) ---------------------------------------------

    override fun observeIdProofs(): Flow<List<IdProofItem>> = proofs.filterNotNull()

    override suspend fun saveIdProof(id: Long?, name: String) {
        requireSuperAdmin()
        val clean = name.trim()
        if (clean.isEmpty()) throw InvalidInputException("Enter the name of the ID proof.")
        if (id == null) api.createIdProof(clean, maskFlag = false) else api.updateIdProof(id, clean)
        loadProofs()
    }

    override suspend fun deleteIdProof(id: Long) {
        requireSuperAdmin()
        api.deleteIdProof(id)
        loadProofs()
    }

    // ---- Header / footer / logo ---------------------------------------------------------------

    override fun observeHeaderFooter(): Flow<List<HeaderFooterItem>> = lines.filterNotNull()

    override suspend fun saveHeaderFooter(item: HeaderFooterItem) {
        requireEditor()
        if (item.type == LineType.LOGO) {
            val existing = lineRows.firstOrNull { it.id == item.id }
            val image = item.image ?: throw InvalidInputException("Choose a picture for the logo.")
            val unchanged = existing?.image?.let { decode(it).contentEquals(image) } == true
            if (unchanged) {
                api.updateLineFlags(item.id, item.onReceipt, item.onBill, item.onReport)
            } else {
                if (image.size > MAX_LOGO_BYTES) throw InvalidInputException("The logo picture is too large (max 200 KB).")
                api.uploadLogo(customerId, image, item.onReceipt, item.onBill, item.onReport)
            }
        } else {
            val text = item.text.trim()
            if (text.isEmpty()) throw InvalidInputException("Enter the text of the line.")
            val existing = lineRows.firstOrNull { it.id == item.id }
            if (existing == null) {
                val order = (lineRows.filter { it.lineType == item.type.code }.maxOfOrNull { it.lineOrder } ?: 0) + 1
                api.createLine(customerId, item.type.code, order, text, item.onReceipt, item.onBill, item.onReport)
            } else {
                api.updateLine(item.id, existing.lineOrder, text, item.onReceipt, item.onBill, item.onReport)
            }
        }
        loadLines()
    }

    override suspend fun deleteHeaderFooter(id: Long) {
        requireEditor()
        api.deleteLine(id)
        loadLines()
    }

    /** Swaps print order with the neighbour of the same type (orders renumbered 1, 2, 3, …). */
    override suspend fun moveHeaderFooter(id: Long, up: Boolean) {
        requireEditor()
        val row = lineRows.firstOrNull { it.id == id } ?: return
        val same = lineRows.filter { it.lineType == row.lineType }.toMutableList()
        val i = same.indexOf(row)
        val j = if (up) i - 1 else i + 1
        if (j !in same.indices) return
        same[i] = same[j].also { same[j] = same[i] }
        same.forEachIndexed { index, r -> if (r.lineOrder != index + 1) api.updateLineOrder(r.id, index + 1) }
        loadLines()
    }

    // ---- Shifts -------------------------------------------------------------------------------

    override fun observeShifts(): Flow<List<ShiftItem>> = shifts.filterNotNull()

    override suspend fun saveShift(id: Long?, name: String, fromTime: String, toTime: String) {
        requireEditor()
        val clean = name.trim()
        val from = normalizeTime(fromTime) ?: throw InvalidInputException("From time must be like 06:00 (24-hour clock).")
        val to = normalizeTime(toTime) ?: throw InvalidInputException("To time must be like 14:00 (24-hour clock).")
        when {
            clean.isEmpty() -> throw InvalidInputException("Enter the shift name.")
            from == to -> throw InvalidInputException("From and To cannot be the same time.")
        }
        if (id == null) api.createShift(customerId, clean, from, to) else api.updateShift(id, clean, from, to)
        loadShifts()
    }

    override suspend fun deleteShift(id: Long) {
        requireEditor()
        api.deleteShift(id)
        loadShifts()
    }

    private fun HeaderFooterDto.toItem() = HeaderFooterItem(
        id = id,
        type = LineType.entries.firstOrNull { it.code == lineType } ?: LineType.HEADER,
        text = narration.orEmpty(),
        image = image?.let(::decode),
        onReceipt = inFlag == "Y",
        onBill = outFlag == "Y",
        onReport = reportFlag == "Y",
    )

    private fun decode(base64: String): ByteArray = Base64.decode(base64, Base64.DEFAULT)

    private companion object {
        /** Print order: logo, header lines, footer lines. */
        val TYPE_ORDER = listOf("L", "H", "F")

        /** API limit for the logo upload. */
        const val MAX_LOGO_BYTES = 200 * 1024

        /** "6:00" → "06:00"; null when not a valid 24-hour time. */
        fun normalizeTime(text: String): String? {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text.trim()) ?: return null
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h in 0..23 && min in 0..59) "%02d:%02d".format(h, min) else null
        }
    }
}

/** `name@bank` as the API checks it (letters, digits, dot, dash, underscore before the @). */
private val UPI_ID = Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9.-]{1,63}$")
