package com.synergic.cloakroom.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.synergic.cloakroom.domain.remote.Connectivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the phone has a network. "Internet" capability only, not "validated": the dev backend
 * sits on an office Wi-Fi that may have no internet beyond it.
 */
@Singleton
class AndroidConnectivity @Inject constructor(
    @ApplicationContext context: Context,
) : Connectivity {

    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(check())
    override val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        runCatching {
            manager?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = check() }
                override fun onLost(network: Network) { _online.value = check() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _online.value = check() }
            })
        }
    }

    private fun check(): Boolean = runCatching {
        val caps = manager?.getNetworkCapabilities(manager.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    override fun isOnline(): Boolean = check().also { _online.value = it }
}
