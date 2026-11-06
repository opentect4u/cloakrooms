package com.synergic.cloakroom.domain.repository

import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.PrinterType
import com.synergic.cloakroom.domain.settings.SettingDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Reads and writes md_setting. [userId] is md_user.id, or null for the customer-wide values. */
interface SettingsRepository {
    /**
     * Settings of the logged-in user (customer-wide when logged out). Every screen collects this,
     * so a change made by the admin applies at once. Null until loaded, or if a row is invalid.
     */
    val currentSettings: StateFlow<AppSettings?>

    fun observeSettings(userId: Long?): Flow<AppSettings>

    suspend fun getSettings(userId: Long?): AppSettings

    /** The customer-wide rows, in sort_order, for the Settings screen. */
    fun observeDefinitions(): Flow<List<SettingDefinition>>

    /**
     * Admin only. Saves [changes] (setting name → new value; PASSWORD values in plain text, they
     * are hashed here) to the customer-wide rows and writes one td_setting_log row per change.
     * Returns the number of settings that actually changed.
     *
     * @throws com.synergic.cloakroom.domain.auth.NotAuthorizedException for normal users.
     * @throws com.synergic.cloakroom.domain.settings.SettingsValidationException for invalid values.
     */
    suspend fun updateSettings(changes: Map<String, String>): Int

    /**
     * The Print tab (setting_type P) as the logged-in user sees it: labels and limits from the
     * customer-wide rows, values with the user's own rows applied.
     */
    fun observePrintDefinitions(): Flow<List<SettingDefinition>>

    /**
     * Any logged-in user (Settings → Print, Printer setup). An admin changes the customer-wide
     * rows (the default for every user, same as [updateSettings]); a normal user may change only
     * Print settings (setting_type P), saved as their own override rows (user_id = their
     * md_user.id). One td_setting_log row per change. Returns the number of settings changed.
     *
     * @throws com.synergic.cloakroom.domain.auth.NotAuthorizedException when nobody is logged in,
     *   or a normal user sends a non-Print setting.
     * @throws com.synergic.cloakroom.domain.settings.SettingsValidationException for invalid values.
     */
    suspend fun updatePrintSettings(changes: Map<String, String>): Int

    /** Printer setup: PRINTER_TYPE and BT_PRINTER through [updatePrintSettings]. */
    suspend fun savePrinterChoice(printerType: PrinterType, btPrinter: String): Int
}
