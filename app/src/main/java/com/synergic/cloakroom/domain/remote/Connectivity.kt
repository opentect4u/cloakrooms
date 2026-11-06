package com.synergic.cloakroom.domain.remote

import kotlinx.coroutines.flow.StateFlow

/** Network state. Super admins and admins may work only while [online] is true. */
interface Connectivity {
    val online: StateFlow<Boolean>
    fun isOnline(): Boolean
}
