package com.synergic.cloakroom.domain.remote

/**
 * A refused or failed API call (API_GUIDE.md §2). [code] is the envelope `code` (e.g.
 * `VALIDATION_ERROR`, `SESSION_ACTIVE`), or [NETWORK] when the server could not be reached.
 * [message] is ready for the screen; [fieldErrors] maps a body field to its validation message.
 */
class ServerException(
    val code: String,
    message: String,
    val httpStatus: Int = 0,
    val fieldErrors: Map<String, String> = emptyMap(),
    /** The envelope `errors` object (e.g. session_started_at, renewal_dt), as text values. */
    val details: Map<String, String> = emptyMap(),
    val requestId: String? = null,
) : RuntimeException(message) {

    companion object {
        const val NETWORK = "NETWORK"
        const val VALIDATION_ERROR = "VALIDATION_ERROR"
        const val UNAUTHORIZED = "UNAUTHORIZED"
        const val TOKEN_EXPIRED = "TOKEN_EXPIRED"
        const val TOKEN_REUSED = "TOKEN_REUSED"
        const val SESSION_ENDED = "SESSION_ENDED"
        const val FORBIDDEN = "FORBIDDEN"
        const val SUBSCRIPTION_EXPIRED = "SUBSCRIPTION_EXPIRED"
        const val NOT_FOUND = "NOT_FOUND"
        const val CONFLICT = "CONFLICT"
        const val SESSION_ACTIVE = "SESSION_ACTIVE"
        const val USER_LIMIT_REACHED = "USER_LIMIT_REACHED"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED"
        const val UPDATE_REQUIRED = "UPDATE_REQUIRED"

        /** Codes after which the stored session is no longer usable: back to login. */
        val SESSION_OVER = setOf(SESSION_ENDED, TOKEN_REUSED, UNAUTHORIZED)
    }
}
