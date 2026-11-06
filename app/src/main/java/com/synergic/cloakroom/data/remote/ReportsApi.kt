package com.synergic.cloakroom.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

// Reports (API_GUIDE.md §9), field names exactly as on the wire.

/** A row of check-in-register / bags-in-store (and the bag part of check-out-register). */
@Serializable
data class ReportBagDto(
    @SerialName("trans_id") val transId: String,
    @SerialName("receipt_no") val receiptNo: String,
    @SerialName("receipt_dt") val receiptDt: String,
    @SerialName("date_time_in") val dateTimeIn: String,
    @SerialName("user_id_in") val userIdIn: Long,
    @SerialName("user_name_in") val userNameIn: String? = null,
    @SerialName("shift_name_in") val shiftNameIn: String? = null,
    @SerialName("pasn_name") val pasnName: String,
    @SerialName("pasn_mob_no") val pasnMobNo: String,
    @SerialName("pnr_no") val pnrNo: String = "",
    @SerialName("luggage_qty") val luggageQty: Int,
    @SerialName("out_flag") val outFlag: String = "N",
    @SerialName("date_time_out") val dateTimeOut: String? = null,
    @SerialName("user_id_out") val userIdOut: Long? = null,
    @SerialName("shift_name_out") val shiftNameOut: String? = null,
    @SerialName("bill_no") val billNo: String? = null,
    @SerialName("bill_dt") val billDt: String? = null,
    @SerialName("net_amt") val netAmt: String? = null,
    @SerialName("pay_mode") val payMode: String? = null,
    @SerialName("bill_printed") val billPrinted: String? = null,
)

/** A sync exception (§9): the row the server could not take, with the pushed row in [payload]. */
@Serializable
data class SyncExceptionDto(
    val id: Long,
    @SerialName("trans_id") val transId: String,
    @SerialName("table_name") val tableName: String,
    val reason: String,
    val payload: JsonObject? = null,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("resolved_flag") val resolvedFlag: String = "N",
    @SerialName("created_at") val createdAt: String,
)

/** Report rows with `meta.total` and `meta.totals` (money as strings). */
data class ReportResult<T>(val rows: List<T>, val total: Int, val totals: JsonObject?) {
    fun int(name: String): Int = totals?.get(name)?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    fun money(name: String): String = totals?.get(name)?.jsonPrimitive?.contentOrNull ?: "0.00"
}

/**
 * The station admin's (and super admin's) reports from the server. [customerId] is required for a
 * super admin and ignored for others. [reportPassword] goes in `X-Report-Password`.
 */
@Singleton
class ReportsApi @Inject constructor(private val api: ApiClient) {

    suspend fun checkinRegister(q: ReportQuery) = rows("/reports/check-in-register", q)

    suspend fun checkoutRegister(q: ReportQuery) = rows("/reports/check-out-register", q)

    suspend fun bagsInStore(q: ReportQuery) = rows("/reports/bags-in-store", q)

    /** Only the totals (bills, bags, base_amt, penal_amt, net_amt, cash_amt, upi_amt). */
    suspend fun collectionTotals(q: ReportQuery): ReportResult<Unit> = totals("/reports/collection-summary", q)

    /** Only the totals (bills, taxable_amt, cgst, sgst, igst, net_amt). */
    suspend fun gstTotals(q: ReportQuery): ReportResult<Unit> = totals("/reports/gst", q)

    /** Sync exceptions (§9), newest first; [resolved] "N" for the ones still open. */
    suspend fun exceptions(q: ReportQuery, resolved: String?): List<SyncExceptionDto> {
        val all = mutableListOf<SyncExceptionDto>()
        var page = 1
        while (true) {
            val r = api.getReport("/reports/exceptions", SyncExceptionDto.serializer(), q.query(page, MAX_LIMIT) + ("resolved" to resolved), q.headers())
            all += r.items
            if (r.items.isEmpty() || all.size >= r.total) return all
            page++
        }
    }

    /** `PATCH /reports/exceptions/:id/resolve` (A, S; idempotent). */
    suspend fun resolveException(id: Long) {
        api.patch("/reports/exceptions/$id/resolve", JsonObject(emptyMap()), JsonObject.serializer())
    }

    /** All pages of a register (≤ 200 rows per page). */
    private suspend fun rows(path: String, q: ReportQuery): ReportResult<ReportBagDto> {
        val all = mutableListOf<ReportBagDto>()
        var page = 1
        while (true) {
            val r = api.getReport(path, ReportBagDto.serializer(), q.query(page, MAX_LIMIT), q.headers())
            all += r.items
            if (r.items.isEmpty() || all.size >= r.total) return ReportResult(all, r.total, r.totals)
            page++
        }
    }

    private suspend fun totals(path: String, q: ReportQuery): ReportResult<Unit> {
        val r = api.getReport(path, JsonObject.serializer(), q.query(1, 1), q.headers())
        return ReportResult(emptyList(), r.total, r.totals)
    }

    private companion object {
        const val MAX_LIMIT = 200
    }
}

/** Common report query (§9): IST dates, optional user / shift / pay mode. */
data class ReportQuery(
    val from: String? = null,
    val to: String? = null,
    val userId: Long? = null,
    val shiftId: Long? = null,
    val payMode: String? = null,
    val customerId: Long? = null,
    val reportPassword: String? = null,
) {
    fun query(page: Int, limit: Int): Map<String, Any?> = mapOf(
        "from" to from, "to" to to, "user_id" to userId, "shift_id" to shiftId, "pay_mode" to payMode,
        "customer_id" to customerId, "page" to page, "limit" to limit,
    )

    fun headers(): Map<String, String> = reportPassword?.let { mapOf("X-Report-Password" to it) }.orEmpty()
}
