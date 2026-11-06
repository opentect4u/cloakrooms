package com.synergic.cloakroom.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** What the last sync did, for the Dashboard line. */
data class SyncStatus(
    val running: Boolean = false,
    /** UTC db text of the last sync that reached the server, or null. */
    val lastSyncAt: String? = null,
    /** Message of the last failed try (stays until a sync works). */
    val lastError: String? = null,
)

sealed interface SyncOutcome {
    /** Pushed and pulled; [pending] rows are still waiting (normally 0). */
    data class Done(val pushed: Int, val pending: Int) : SyncOutcome
    data object Offline : SyncOutcome
    /** No counter session (logged out, super admin). */
    data object NotLoggedIn : SyncOutcome
    data class Failed(val message: String) : SyncOutcome
}

/**
 * Offline sync (API_GUIDE.md §6): rows saved on the phone have `sync_flag = N`; a push sends them
 * and only the server's answer sets `Y`; a pull brings masters, settings, counters and other
 * counters' bags. Runs by itself whenever the phone is online (no interval to set).
 */
interface SyncRepository {
    /** Check-ins / check-outs and bills not yet on the server. */
    val pendingCount: Flow<Int>

    val status: StateFlow<SyncStatus>

    /** Push all pending rows, then pull. Safe to call at any time (one sync at a time). */
    suspend fun syncNow(): SyncOutcome

    /** Push only (logout, session ended). Returns the rows still waiting afterwards. */
    suspend fun pushPending(): Int
}
