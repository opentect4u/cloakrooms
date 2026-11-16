package com.synergic.cloakroom.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import javax.inject.Inject
import javax.inject.Singleton

// Offline sync (API_GUIDE.md §6), field names exactly as on the wire.

/** A luggage row as pushed (§6.2). customer_id, sync_flag and audit fields are not sent. */
@Serializable
data class PushLuggageDto(
    @SerialName("trans_id") val transId: String,
    @SerialName("local_version") val localVersion: Int,
    @SerialName("receipt_no") val receiptNo: String,
    @SerialName("receipt_dt") val receiptDt: String,
    @SerialName("user_id_in") val userIdIn: Long,
    @SerialName("device_id_in") val deviceIdIn: String? = null,
    @SerialName("shift_id_in") val shiftIdIn: Long? = null,
    @SerialName("date_time_in") val dateTimeIn: String,
    @SerialName("pasn_name") val pasnName: String,
    @SerialName("pasn_mob_no") val pasnMobNo: String,
    @SerialName("pnr_no") val pnrNo: String,
    @SerialName("luggage_qty") val luggageQty: Int,
    /** Free extra bags; left out of the JSON when 0 (defaults are not encoded). */
    @SerialName("extra_lugage_qty") val extraLugageQty: Int = 0,
    @SerialName("id_proof_id") val idProofId: Long? = null,
    @SerialName("id_number") val idNumber: String? = null,
    @SerialName("user_id_out") val userIdOut: Long? = null,
    @SerialName("device_id_out") val deviceIdOut: String? = null,
    @SerialName("shift_id_out") val shiftIdOut: Long? = null,
    @SerialName("date_time_out") val dateTimeOut: String? = null,
    @SerialName("out_flag") val outFlag: String,
)

/** A bill as pushed (§6.2). Money as 2-decimal strings; gst_no is stamped by the server. */
@Serializable
data class PushReceiptDto(
    @SerialName("trans_id") val transId: String,
    @SerialName("local_version") val localVersion: Int,
    @SerialName("bill_no") val billNo: String,
    @SerialName("bill_dt") val billDt: String,
    @SerialName("user_id") val userId: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("shift_id") val shiftId: Long? = null,
    @SerialName("luggage_qty") val luggageQty: Int,
    @SerialName("days_charged") val daysCharged: Int,
    @SerialName("block_hours") val blockHours: Int,
    @SerialName("first_block_rate") val firstBlockRate: String,
    @SerialName("next_block_rate") val nextBlockRate: String,
    @SerialName("base_amt") val baseAmt: String,
    @SerialName("penal_amt") val penalAmt: String,
    @SerialName("net_amt") val netAmt: String,
    @SerialName("taxable_amt") val taxableAmt: String,
    @SerialName("gst_pct") val gstPct: String,
    val cgst: String,
    val sgst: String,
    val igst: String,
    @SerialName("gst_flag") val gstFlag: String,
    /** Options billed with (§7): the server recomputes and stores them. */
    @SerialName("grace_minutes") val graceMinutes: Int,
    @SerialName("gst_inclusive") val gstInclusive: String,
    @SerialName("penalty_gst_flag") val penaltyGstFlag: String,
    @SerialName("pay_mode") val payMode: String,
    @SerialName("upi_ref") val upiRef: String? = null,
    @SerialName("bill_printed") val billPrinted: String,
)

@Serializable
data class PushRequestDto(
    val luggage: List<PushLuggageDto>,
    val receipts: List<PushReceiptDto>,
)

/** One row's result: ACCEPTED or EXCEPTION — both mean "set sync_flag = Y" for that local_version. */
@Serializable
data class PushResultDto(
    val table: String,
    @SerialName("trans_id") val transId: String,
    @SerialName("local_version") val localVersion: Int,
    val status: String,
    val reason: String? = null,
)

@Serializable
data class PushResponseDto(
    val results: List<PushResultDto> = emptyList(),
    val counters: CountersDto? = null,
    @SerialName("server_time") val serverTime: String,
)

/** A luggage row as pulled (§6.3): other counters' bags and check-outs. */
@Serializable
data class PullLuggageDto(
    @SerialName("trans_id") val transId: String,
    @SerialName("customer_id") val customerId: Long? = null,
    @SerialName("receipt_no") val receiptNo: String,
    @SerialName("receipt_dt") val receiptDt: String,
    @SerialName("user_id_in") val userIdIn: Long,
    @SerialName("device_id_in") val deviceIdIn: String? = null,
    @SerialName("shift_id_in") val shiftIdIn: Long? = null,
    @SerialName("date_time_in") val dateTimeIn: String,
    @SerialName("pasn_name") val pasnName: String,
    @SerialName("pasn_mob_no") val pasnMobNo: String,
    @SerialName("pnr_no") val pnrNo: String = "",
    @SerialName("luggage_qty") val luggageQty: Int,
    /** 0 when the server does not send it (yet). */
    @SerialName("extra_lugage_qty") val extraLugageQty: Int = 0,
    @SerialName("id_proof_id") val idProofId: Long? = null,
    @SerialName("id_number") val idNumber: String? = null,
    @SerialName("user_id_out") val userIdOut: Long? = null,
    @SerialName("device_id_out") val deviceIdOut: String? = null,
    @SerialName("shift_id_out") val shiftIdOut: Long? = null,
    @SerialName("date_time_out") val dateTimeOut: String? = null,
    @SerialName("out_flag") val outFlag: String = "N",
    @SerialName("is_deleted") val isDeleted: String = "N",
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class PullResponseDto(
    val masters: MastersDto? = null,
    val settings: Map<String, String>? = null,
    val counters: CountersDto? = null,
    val luggage: List<PullLuggageDto> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("server_time") val serverTime: String,
)

/** `POST /sync/push` and `GET /sync/pull` (roles A, O; push also after a force logout). */
interface SyncApi {
    suspend fun push(request: PushRequestDto): PushResponseDto

    /** [since] for a new pull, [cursor] for the next page (never both). */
    suspend fun pull(since: String?, cursor: String?): PullResponseDto

    /** GET /customers/me (A, O): the own cloak room; pull does not carry it. */
    suspend fun ownCustomer(): CustomerDto
}

@Singleton
class HttpSyncApi @Inject constructor(private val api: ApiClient) : SyncApi {

    override suspend fun push(request: PushRequestDto): PushResponseDto =
        api.post("/sync/push", ApiClient.json.encodeToJsonElement<PushRequestDto>(request) as JsonElement, PushResponseDto.serializer())

    override suspend fun pull(since: String?, cursor: String?): PullResponseDto =
        api.get("/sync/pull", PullResponseDto.serializer(), mapOf("since" to since, "cursor" to cursor))

    override suspend fun ownCustomer(): CustomerDto = api.get("/customers/me", CustomerDto.serializer())
}
