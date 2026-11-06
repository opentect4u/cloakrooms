package com.synergic.cloakroom.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Request and response shapes from API_GUIDE.md (field names exactly as on the wire). Unknown
// fields are ignored, so the backend may add fields without breaking the app.

@Serializable
data class LoginRequestDto(
    @SerialName("user_id") val userId: String,
    val password: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("app_version") val appVersion: String? = null,
    /** Android versionCode; the server answers 426 UPDATE_REQUIRED when a newer mandatory release exists. */
    @SerialName("version_code") val versionCode: Int? = null,
)

@Serializable
data class ChangePasswordRequestDto(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class ChangePasswordDataDto(@SerialName("must_change_pwd") val mustChangePwd: String = "N")

/** errors.latest of 426 UPDATE_REQUIRED (an app release). */
@Serializable
data class AppReleaseDto(
    @SerialName("version_code") val versionCode: Int? = null,
    @SerialName("version_name") val versionName: String? = null,
    @SerialName("apk_url") val apkUrl: String? = null,
    @SerialName("release_notes") val releaseNotes: String? = null,
)

@Serializable
data class TokenPairDto(
    val accessToken: String,
    val refreshToken: String,
    @SerialName("server_time") val serverTime: String? = null,
)

/** §3 login response. customer/counters are null for a super admin. */
@Serializable
data class LoginDataDto(
    val accessToken: String,
    val refreshToken: String,
    val user: UserDto,
    val customer: CustomerDto? = null,
    val masters: MastersDto? = null,
    val settings: Map<String, String>? = null,
    val counters: CountersDto? = null,
    @SerialName("server_time") val serverTime: String,
)

@Serializable
data class MeDto(
    val user: UserDto,
    val customer: CustomerDto? = null,
    @SerialName("server_time") val serverTime: String? = null,
)

@Serializable
data class UserDto(
    val id: Long,
    @SerialName("customer_id") val customerId: Long? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val userName: String,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("user_type") val userType: String,
    @SerialName("allow_flag") val allowFlag: String = "Y",
    /** Y after an admin created the user or reset the password: change it before anything else. */
    @SerialName("must_change_pwd") val mustChangePwd: String = "N",
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("session_started_at") val sessionStartedAt: String? = null,
    @SerialName("last_login") val lastLogin: String? = null,
)

@Serializable
data class CustomerDto(
    @SerialName("customer_id") val customerId: Long,
    @SerialName("customer_name") val customerName: String,
    @SerialName("customer_addr") val customerAddr: String = "",
    val state: String? = null,
    @SerialName("pin_code") val pinCode: String = "",
    @SerialName("mobile_no") val mobileNo: String = "",
    val email: String? = null,
    @SerialName("gst_no") val gstNo: String? = null,
    @SerialName("upi_id") val upiId: String? = null,
    @SerialName("renewal_dt") val renewalDt: String? = null,
    @SerialName("user_limit") val userLimit: Int = 1,
    @SerialName("active_flag") val activeFlag: String = "Y",
    val remarks: String? = null,
)

@Serializable
data class CustomerSummaryDto(
    val customer: CustomerDto,
    val users: UserCountsDto = UserCountsDto(),
    @SerialName("latest_registration") val latestRegistration: RegistrationDto? = null,
    val payments: PaymentTotalsDto = PaymentTotalsDto(),
    val luggage: LuggageCountsDto = LuggageCountsDto(),
)

@Serializable
data class UserCountsDto(
    val total: Int = 0,
    val allowed: Int = 0,
    val admins: Int = 0,
    val operators: Int = 0,
    @SerialName("active_sessions") val activeSessions: Int = 0,
)

@Serializable
data class PaymentTotalsDto(
    val count: Int = 0,
    @SerialName("total_net") val totalNet: String = "0.00",
    @SerialName("last_payment_dt") val lastPaymentDt: String? = null,
)

@Serializable
data class LuggageCountsDto(
    val total: Int = 0,
    @SerialName("in_store") val inStore: Int = 0,
)

@Serializable
data class RegistrationDto(
    @SerialName("reg_id") val regId: Long,
    @SerialName("reg_no") val regNo: String,
    @SerialName("customer_id") val customerId: Long,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("user_count") val userCount: Int,
    val remarks: String? = null,
)

/** Reset password / force logout answer. */
@Serializable
data class SessionsEndedDto(@SerialName("sessions_ended") val sessionsEnded: Int = 0)
