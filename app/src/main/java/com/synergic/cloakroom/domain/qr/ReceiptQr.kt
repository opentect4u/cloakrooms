package com.synergic.cloakroom.domain.qr

/**
 * What the check-in receipt QR carries (Case 1 in the architecture PDF), so another counter can
 * bill the bag before it has synced. The ID-proof number is never part of it.
 */
data class ReceiptQrData(
    val transId: String,
    val receiptNo: String,
    /** Local date 'YYYY-MM-DD'. */
    val receiptDt: String,
    val customerId: Long,
    /** md_user.id of the check-in user. */
    val userIdIn: Long,
    /** UTC 'YYYY-MM-DD HH:MM:SS'. */
    val dateTimeIn: String,
    val bags: Int,
    val name: String,
    val mobile: String,
    val pnr: String,
)

/**
 * Compact JSON with short keys, which keeps the QR small enough to scan easily from 58 mm paper:
 * `{"v":1,"t":trans_id,"r":receipt_no,"d":receipt_dt,"c":customer_id,"u":user_id_in,
 *   "i":date_time_in,"b":bags,"n":name,"m":mobile,"p":pnr}`
 */
object ReceiptQr {
    const val VERSION = 1L

    fun encode(d: ReceiptQrData): String = CompactJson.encode(
        linkedMapOf(
            "v" to VERSION,
            "t" to d.transId,
            "r" to d.receiptNo,
            "d" to d.receiptDt,
            "c" to d.customerId,
            "u" to d.userIdIn,
            "i" to d.dateTimeIn,
            "b" to d.bags.toLong(),
            "n" to d.name,
            "m" to d.mobile,
            "p" to d.pnr,
        )
    )

    /** Returns null when the text is not a receipt QR of this app. */
    fun decode(text: String): ReceiptQrData? {
        val m = CompactJson.decode(text) ?: return null
        if (m["v"] != VERSION) return null
        return runCatching {
            ReceiptQrData(
                transId = m["t"] as String,
                receiptNo = m["r"] as String,
                receiptDt = m["d"] as String,
                customerId = m["c"] as Long,
                userIdIn = m["u"] as Long,
                dateTimeIn = m["i"] as String,
                bags = (m["b"] as Long).toInt(),
                name = m["n"] as String,
                mobile = m["m"] as String,
                pnr = m["p"] as String,
            )
        }.getOrNull()
    }
}
