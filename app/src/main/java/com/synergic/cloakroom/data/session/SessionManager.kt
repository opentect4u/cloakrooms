package com.synergic.cloakroom.data.session

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.auth.UserSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Holds the current [SessionState]. Only the auth repository changes it. */
@Singleton
class SessionManager @Inject constructor() : SessionProvider {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    fun set(state: SessionState) {
        _state.value = state
    }
}

/** Remembers who is logged in on this device, so the session survives app restarts. */
interface SessionStore {
    fun savedSession(): UserSession?
    fun save(session: UserSession)
    fun clear()
}

/** The API tokens (API_GUIDE.md §3). Never logged. */
interface TokenStore {
    val accessToken: String?
    val refreshToken: String?
    fun saveTokens(accessToken: String, refreshToken: String)
    fun clearTokens()
}

/**
 * Session and tokens in EncryptedSharedPreferences (API_GUIDE.md §3: tokens in secure storage).
 */
@Singleton
class SecureSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SessionStore, TokenStore {

    private val prefs: SharedPreferences by lazy { open() }

    private fun open(): SharedPreferences = try {
        create()
    } catch (e: Exception) {
        // A Keystore key lost after a backup restore makes the old file unreadable: start again.
        Log.w(TAG, "Secure storage could not be opened; it is reset", e)
        context.deleteSharedPreferences(FILE)
        create()
    }

    private fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun savedSession(): UserSession? =
        prefs.getString(KEY_SESSION, null)?.let { runCatching { json.decodeFromString<StoredSession>(it).toSession() }.getOrNull() }

    override fun save(session: UserSession) {
        prefs.edit().putString(KEY_SESSION, json.encodeToString(StoredSession.from(session))).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
    }

    override val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)
    override val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)

    override fun saveTokens(accessToken: String, refreshToken: String) {
        // commit: a refreshed pair must be on disk before the old one stops working.
        prefs.edit().putString(KEY_ACCESS, accessToken).putString(KEY_REFRESH, refreshToken).commit()
    }

    override fun clearTokens() {
        prefs.edit().remove(KEY_ACCESS).remove(KEY_REFRESH).apply()
    }

    @Serializable
    private data class StoredSession(
        val id: Long,
        val userId: String,
        val userName: String,
        val userType: String,
        val userCode: String,
        val sessionStartedAt: String,
        val customerId: Long? = null,
        val mustChangePassword: Boolean = false,
    ) {
        fun toSession() = UserSession(id, userId, userName, userType, userCode, sessionStartedAt, mustChangePassword, customerId)

        companion object {
            fun from(s: UserSession) =
                StoredSession(s.id, s.userId, s.userName, s.userType, s.userCode, s.sessionStartedAt, s.customerId, s.mustChangePassword)
        }
    }

    private companion object {
        const val TAG = "SecureSessionStore"
        const val FILE = "secure_session"
        const val KEY_SESSION = "session"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        val json = Json { ignoreUnknownKeys = true }
    }
}
