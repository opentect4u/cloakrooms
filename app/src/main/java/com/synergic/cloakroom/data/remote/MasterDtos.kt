package com.synergic.cloakroom.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Customer masters and settings (API_GUIDE.md §5), field names exactly as on the wire.

@Serializable
data class RateDto(
    @SerialName("rate_id") val rateId: Long,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("block_hours") val blockHours: Int,
    @SerialName("first_block_rate") val firstBlockRate: String,
    @SerialName("next_block_rate") val nextBlockRate: String,
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("is_deleted") val isDeleted: String = "N",
)

@Serializable
data class ShiftDto(
    @SerialName("shift_id") val shiftId: Long,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("shift_name") val shiftName: String,
    @SerialName("from_time") val fromTime: String,
    @SerialName("to_time") val toTime: String,
    @SerialName("is_deleted") val isDeleted: String = "N",
)

@Serializable
data class ShiftAssignmentDto(
    val id: Long,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("user_id") val userId: Long,
    @SerialName("shift_id") val shiftId: Long,
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("is_deleted") val isDeleted: String = "N",
)

@Serializable
data class HeaderFooterDto(
    val id: Long,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("line_type") val lineType: String,
    @SerialName("line_order") val lineOrder: Int = 0,
    val narration: String? = null,
    /** Base64 PNG for the logo row (L). */
    val image: String? = null,
    @SerialName("in_flag") val inFlag: String = "Y",
    @SerialName("out_flag") val outFlag: String = "Y",
    @SerialName("report_flag") val reportFlag: String = "N",
    @SerialName("is_deleted") val isDeleted: String = "N",
)

@Serializable
data class SettingDto(
    @SerialName("setting_id") val settingId: Long,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("setting_name") val settingName: String,
    @SerialName("setting_value") val settingValue: String,
    @SerialName("setting_type") val settingType: String? = null,
)

@Serializable
data class IdProofDto(
    @SerialName("id_proof_id") val idProofId: Long,
    @SerialName("id_proof_name") val idProofName: String,
    @SerialName("mask_flag") val maskFlag: String = "N",
    @SerialName("is_deleted") val isDeleted: String = "N",
)

@Serializable
data class GstDto(
    @SerialName("gst_id") val gstId: Long,
    @SerialName("cgst_pct") val cgstPct: String = "0.00",
    @SerialName("sgst_pct") val sgstPct: String = "0.00",
    @SerialName("igst_pct") val igstPct: String = "0.00",
    @SerialName("effective_from") val effectiveFrom: String,
    @SerialName("is_deleted") val isDeleted: String = "N",
)

/** masters.users (backend A5): no login id, only what counters need to show names. */
@Serializable
data class MasterUserDto(
    val id: Long,
    @SerialName("customer_id") val customerId: Long? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("user_name") val userName: String,
    @SerialName("user_type") val userType: String,
    @SerialName("allow_flag") val allowFlag: String = "Y",
    @SerialName("is_deleted") val isDeleted: String = "N",
)

/** The masters of login and pull (API_GUIDE.md §3, §6.3). */
@Serializable
data class MastersDto(
    @SerialName("id_proofs") val idProofs: List<IdProofDto> = emptyList(),
    val gst: List<GstDto> = emptyList(),
    val rates: List<RateDto> = emptyList(),
    val shifts: List<ShiftDto> = emptyList(),
    @SerialName("shift_assignments") val shiftAssignments: List<ShiftAssignmentDto> = emptyList(),
    @SerialName("header_footer") val headerFooter: List<HeaderFooterDto> = emptyList(),
    val users: List<MasterUserDto> = emptyList(),
)

/** The server's last receipt / bill numbers of the current period for this user (§7). */
@Serializable
data class CountersDto(
    val reset: String? = null,
    @SerialName("period_key") val periodKey: String,
    @SerialName("receipt_last_no") val receiptLastNo: Int = 0,
    @SerialName("bill_last_no") val billLastNo: Int = 0,
)
