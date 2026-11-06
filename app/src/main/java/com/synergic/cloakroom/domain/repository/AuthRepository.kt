package com.synergic.cloakroom.domain.repository

import com.synergic.cloakroom.domain.auth.LoginResult
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.auth.UserSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Login, session and user management. Admin-only methods throw
 * [com.synergic.cloakroom.domain.auth.NotAuthorizedException] for normal users; refused requests
 * throw [com.synergic.cloakroom.domain.auth.AuthException] with a message for the screen.
 */
interface AuthRepository {
    val sessionState: StateFlow<SessionState>

    /** Restores the session saved on this device (app restart). */
    suspend fun restoreSession()

    suspend fun login(userId: String, password: String): LoginResult

    suspend fun logout()

    suspend fun changePassword(currentPassword: String, newPassword: String)

    /** Ends the local session without touching md_user (used after "Reset demo data"). */
    fun clearLocalSession(message: String? = null)

    // Admin only

    fun observeUsers(): Flow<List<UserSummary>>

    /** [shiftId] = md_shift id for a counter user's login shift, null = any time (always null for admins). */
    suspend fun addUser(userId: String, userName: String, userType: String, firstPassword: String, shiftId: Long? = null)

    suspend fun updateUser(id: Long, userName: String, userType: String, shiftId: Long? = null)

    /** Active shifts to choose from when assigning a login shift. */
    suspend fun shiftOptions(): List<ShiftItem>

    suspend fun setBlocked(id: Long, blocked: Boolean)

    suspend fun resetPassword(id: Long, newPassword: String)

    suspend fun forceLogout(id: Long)
}
