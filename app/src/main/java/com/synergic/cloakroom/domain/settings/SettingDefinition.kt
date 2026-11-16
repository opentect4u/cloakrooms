package com.synergic.cloakroom.domain.settings

enum class SettingDataType { BOOL, INT, DECIMAL, TEXT, ENUM, PASSWORD }

/** One md_setting row with everything the Settings screen needs to show and validate it. */
data class SettingDefinition(
    val name: String,
    val value: String,
    /** G = general, B = billing, P = print. */
    val group: String,
    val label: String,
    val dataType: SettingDataType,
    /** ENUM choices as (code, label), parsed from e.g. `C:Cash|U:UPI`. */
    val options: List<Pair<String, String>>,
    val minValue: Double?,
    val maxValue: Double?,
    val dependsOn: String?,
    val sortOrder: Int,
    val description: String?,
    /** Shown but not editable by this user (e.g. a super-admin-only setting seen by an admin). */
    val readOnly: Boolean = false,
    /** Who sets a [readOnly] setting, shown under it; null = the super admin. */
    val readOnlyNote: String? = null,
) {
    companion object {
        fun parseOptions(options: String?): List<Pair<String, String>> =
            options.orEmpty().split("|").filter { it.isNotBlank() }.map { part ->
                val code = part.substringBefore(":").trim()
                code to part.substringAfter(":", code).trim()
            }
    }
}

/** The admin's changes were refused; [errors] maps setting name → message for the screen. */
class SettingsValidationException(val errors: Map<String, String>) :
    RuntimeException(errors.entries.joinToString("; ") { "${it.key}: ${it.value}" })
