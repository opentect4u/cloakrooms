package com.synergic.cloakroom.domain.repository

import com.synergic.cloakroom.domain.settings.SettingDefinition

/**
 * A customer's settings for the super admin, in the same shape as the admin's Settings screen
 * (Billing / General / Print tabs). API_GUIDE.md §8 keys live on the server; the others have no
 * API yet and are kept for that customer on this phone.
 */
interface CustomerSettingsRepository {
    /** True when [name] is saved on the server (a §8 key). */
    fun isOnServer(name: String): Boolean

    suspend fun definitions(customerId: Long): List<SettingDefinition>

    /**
     * Saves [changes] (name → new value). Returns how many were saved.
     * @throws com.synergic.cloakroom.domain.settings.SettingsValidationException for invalid values.
     */
    suspend fun save(customerId: Long, changes: Map<String, String>): Int
}
