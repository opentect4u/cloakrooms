package com.synergic.cloakroom.domain.auth

/** The logged-in user. [id] is md_user.id; [userId] is the login ID. */
data class UserSession(
    val id: Long,
    val userId: String,
    val userName: String,
    val userType: String,
    val userCode: String,
    /** UTC db text. */
    val sessionStartedAt: String,
    val mustChangePassword: Boolean,
    /** md_customer.customer_id; null for a super admin (no customer). */
    val customerId: Long? = null,
) {
    val isAdmin: Boolean get() = userType == UserTypes.ADMIN
    val isSuperAdmin: Boolean get() = userType == UserTypes.SUPER_ADMIN

    /** Super admins and admins work only online; counter users may continue offline. */
    val needsOnline: Boolean get() = isAdmin || isSuperAdmin
}

object UserTypes {
    const val SUPER_ADMIN = "S"
    const val ADMIN = "A"
    const val OPERATOR = "O"
}

sealed interface SessionState {
    data object Loading : SessionState

    /** [message] explains why the user is on the login screen, e.g. a forced logout. */
    data class LoggedOut(val message: String? = null) : SessionState

    data class LoggedIn(val session: UserSession) : SessionState
}

sealed interface LoginResult {
    data class Success(val session: UserSession) : LoginResult
    data class Failure(val message: String) : LoginResult

    /** 426 UPDATE_REQUIRED: this app version may no longer log in. [downloadUrl] is absolute. */
    data class UpdateRequired(val versionName: String?, val downloadUrl: String?, val releaseNotes: String?) : LoginResult
}

/** A row of the Users screen. */
data class UserSummary(
    val id: Long,
    val userId: String,
    val userName: String,
    val userType: String,
    val userCode: String,
    val allowed: Boolean,
    val failedAttempts: Int,
    val mustChangePassword: Boolean,
    /** UTC db text, or null when the user is not logged in. */
    val sessionStartedAt: String?,
    /** md_shift id of the current assignment (td_assign_shift); null = may log in any time. */
    val shiftId: Long? = null,
    /** e.g. "Morning (06:00–14:00)"; null = any time. */
    val shiftLabel: String? = null,
) {
    val isAdmin: Boolean get() = userType == UserTypes.ADMIN
    val isLoggedIn: Boolean get() = sessionStartedAt != null
}

/** An admin-only action was attempted by a user who is not an admin (or nobody is logged in). */
class NotAuthorizedException(message: String = "Only an admin can do this.") : RuntimeException(message)

/** A user action was refused for a reason the user can fix; [message] is shown on screen. */
class AuthException(message: String) : RuntimeException(message)
