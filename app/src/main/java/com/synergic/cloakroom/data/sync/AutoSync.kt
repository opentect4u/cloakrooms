package com.synergic.cloakroom.data.sync

import com.synergic.cloakroom.di.ApplicationScope
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.remote.Connectivity
import com.synergic.cloakroom.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the sync by itself while a counter user (or station admin) is logged in and the phone is
 * online — nothing to set (your rule):
 * - at login, at app start and whenever the internet comes back;
 * - a moment after each new check-in, check-out or bill (pending rows);
 * - and regularly, to receive other counters' bags and the admin's changes and to retry rows a
 *   failed try left waiting. The pause is the server's SYNC_INTERVAL_MIN (not shown in Settings).
 */
@Singleton
class AutoSync @Inject constructor(
    private val sync: SyncManager,
    private val sessionProvider: SessionProvider,
    private val connectivity: Connectivity,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(sessionProvider.state, connectivity.online) { state, online ->
                (state as? SessionState.LoggedIn)?.session?.takeIf { online && !it.isSuperAdmin && it.customerId != null }
            }.distinctUntilChanged().collectLatest { session ->
                if (session == null) return@collectLatest
                sync.syncNow()
                coroutineScope {
                    launch {
                        // Room re-emits on every change; only a new count means new rows to send.
                        sync.pendingCount.distinctUntilChanged().drop(1).filter { it > 0 }.debounce(AFTER_SAVE_MS).collect { sync.syncNow() }
                    }
                    launch {
                        while (true) {
                            delay(settingsRepository.getSettings(session.id).syncIntervalMin.coerceAtLeast(1) * 60_000L)
                            sync.syncNow()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        /** Lets a check-out's bill and luggage row both be saved before they are sent together. */
        const val AFTER_SAVE_MS = 2_000L
    }
}
