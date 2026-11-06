package com.synergic.cloakroom.domain.auth

import kotlinx.coroutines.flow.StateFlow

/** Who is logged in right now. Repositories use it for the permission checks. */
interface SessionProvider {
    val state: StateFlow<SessionState>

    val current: UserSession?
        get() = (state.value as? SessionState.LoggedIn)?.session

    /** @throws NotAuthorizedException when nobody is logged in. */
    fun requireLogin(): UserSession =
        current ?: throw NotAuthorizedException("Please log in first.")

    /** @throws NotAuthorizedException unless the logged-in user is an admin (user_type 'A'). */
    fun requireAdmin(): UserSession {
        val session = requireLogin()
        if (!session.isAdmin) throw NotAuthorizedException()
        return session
    }
}
