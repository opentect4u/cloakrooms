package com.synergic.cloakroom.domain.repository

import kotlinx.coroutines.flow.Flow

/** md_header_footer line type. */
enum class LineType(val code: String, val label: String) { HEADER("H", "Header"), FOOTER("F", "Footer"), LOGO("L", "Logo") }

data class IdProofItem(val id: Long, val name: String)

data class ShiftItem(val id: Long, val name: String, val fromTime: String, val toTime: String)

/** One md_header_footer row. [image] is a black-and-white PNG (logo rows only). */
data class HeaderFooterItem(
    val id: Long,
    val type: LineType,
    val text: String,
    val image: ByteArray?,
    val onReceipt: Boolean,
    val onBill: Boolean,
    val onReport: Boolean,
) {
    // ByteArray needs content equality, so every field is compared here.
    override fun equals(other: Any?): Boolean = other is HeaderFooterItem &&
        id == other.id && type == other.type && text == other.text &&
        onReceipt == other.onReceipt && onBill == other.onBill && onReport == other.onReport &&
        image.contentEquals(other.image)

    override fun hashCode(): Int =
        listOf(id, type, text, onReceipt, onBill, onReport).hashCode() * 31 + image.contentHashCode()
}

/**
 * md_customer as the admin sees it. Name, address, PIN, GSTIN and UPI ID can be changed by the
 * admin; mobile, email, renewal date and user limit are set by the Synergic super admin.
 */
data class CustomerProfile(
    val name: String,
    val address: String,
    val pinCode: String,
    val gstNo: String,
    val upiId: String,
    // Read-only for the customer admin:
    val mobile: String,
    val email: String,
    val renewalDt: String,
    val userLimit: Int,
)

/** The admin typed something that cannot be saved; [message] is shown on screen. */
class InvalidInputException(message: String) : RuntimeException(message)

/**
 * Admin → Masters: the cloak room's details, ID proof types, header/footer/logo lines and shifts.
 * Every write is admin only ([com.synergic.cloakroom.domain.auth.NotAuthorizedException]);
 * nothing is hard-deleted (is_deleted = 'Y').
 */
interface MasterRepository {
    /** The cloak room's own details (customer 1); null if missing. */
    fun observeCustomer(): Flow<CustomerProfile?>

    /** Saves only the admin-editable fields. Empty GSTIN or UPI ID means "none". */
    suspend fun updateCustomer(name: String, address: String, pinCode: String, gstNo: String, upiId: String)

    fun observeIdProofs(): Flow<List<IdProofItem>>

    /** [id] null = add. */
    suspend fun saveIdProof(id: Long?, name: String)

    suspend fun deleteIdProof(id: Long)

    /** In print order. */
    fun observeHeaderFooter(): Flow<List<HeaderFooterItem>>

    /** [item].id 0 = add. */
    suspend fun saveHeaderFooter(item: HeaderFooterItem)

    suspend fun deleteHeaderFooter(id: Long)

    /** Swaps the line with the previous ([up]) or next line of the same type. */
    suspend fun moveHeaderFooter(id: Long, up: Boolean)

    fun observeShifts(): Flow<List<ShiftItem>>

    /** [id] null = add. Times are 'HH:MM' (24 h) and may cross midnight. */
    suspend fun saveShift(id: Long?, name: String, fromTime: String, toTime: String)

    suspend fun deleteShift(id: Long)
}
