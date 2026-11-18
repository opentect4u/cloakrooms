package com.synergic.cloakroom.ui.settings

import com.synergic.cloakroom.domain.billing.RateSlabs
import com.synergic.cloakroom.domain.settings.SettingDataType
import com.synergic.cloakroom.domain.settings.SettingDefinition
import com.synergic.cloakroom.domain.settings.SettingKeys
import com.synergic.cloakroom.domain.settings.SettingValidator

/** One field on the Settings screen. */
data class SettingField(
    val def: SettingDefinition,
    /** Text shown in the field: the draft, or the saved value (PASSWORD fields start empty). */
    val value: String,
    /** False when the depends_on switch is off or the field is read-only: it is greyed out. */
    val enabled: Boolean,
    val changed: Boolean,
    val error: String?,
    /** Label of the depends_on switch, for the "needs …" hint. */
    val parentLabel: String?,
)

data class SettingsTab(val group: String, val title: String, val fields: List<SettingField>) {
    val errorCount: Int get() = fields.count { it.error != null }
}

/** A line of the "Save these changes?" dialog. */
data class ChangeLine(val label: String, val from: String, val to: String)

/**
 * Pure form logic of the Settings screen: builds tabs from the md_setting rows and the unsaved
 * [drafts] (name → new value), so a new setting row needs no screen code.
 */
object SettingsForm {

    private val GROUP_TITLES = linkedMapOf("B" to "Billing", "G" to "General", "P" to "Print")

    /**
     * Settings with their own screen, left off the tabs: PRINTER_TYPE and BT_PRINTER are set in
     * Printer setup (device list, test print; open to every user).
     */
    // SYNC_INTERVAL_MIN: sync runs by itself whenever the phone is online (your rule), so no interval to set.
    val SHOWN_ELSEWHERE = setOf(SettingKeys.PRINTER_TYPE, SettingKeys.BT_PRINTER, SettingKeys.SYNC_INTERVAL_MIN)

    fun tabs(
        definitions: List<SettingDefinition>,
        drafts: Map<String, String>,
        serverErrors: Map<String, String> = emptyMap(),
    ): List<SettingsTab> {
        val byName = definitions.associateBy { it.name }
        val errors = SettingValidator.validateChanges(definitions, drafts) + serverErrors
        val groups = GROUP_TITLES.keys + definitions.map { it.group }.filter { it !in GROUP_TITLES }.distinct()
        return groups.mapNotNull { group ->
            val fields = definitions.filter { it.group == group && it.name !in SHOWN_ELSEWHERE }.sortedBy { it.sortOrder }.map { def ->
                SettingField(
                    def = def,
                    value = drafts[def.name] ?: if (def.dataType == SettingDataType.PASSWORD) "" else def.value,
                    enabled = !def.readOnly && isEnabled(def, byName, drafts),
                    changed = def.name in drafts,
                    error = errors[def.name],
                    parentLabel = def.dependsOn?.let { byName[it]?.label ?: it },
                )
            }
            if (fields.isEmpty()) null else SettingsTab(group, GROUP_TITLES[group] ?: group, fields)
        }
    }

    /**
     * Applies an edit. An edit back to the saved value (or an empty PASSWORD field) drops the draft.
     * Switching a BOOL off drops the drafts of the settings that depend on it.
     */
    fun edit(definitions: List<SettingDefinition>, drafts: Map<String, String>, name: String, value: String): Map<String, String> {
        val def = definitions.firstOrNull { it.name == name } ?: return drafts
        val result = drafts.toMutableMap()
        val unchanged = when (def.dataType) {
            SettingDataType.PASSWORD -> value.isEmpty()
            SettingDataType.SLABS -> RateSlabs.canonical(value) == RateSlabs.canonical(def.value)
            else -> value == def.value
        }
        if (unchanged) result.remove(name) else result[name] = value
        if (def.dataType == SettingDataType.BOOL && value == "N") {
            dependentsOf(name, definitions).forEach { result.remove(it) }
        }
        return result
    }

    fun changeLines(definitions: List<SettingDefinition>, drafts: Map<String, String>): List<ChangeLine> =
        definitions.filter { it.name in drafts }.sortedWith(compareBy({ it.group }, { it.sortOrder })).map { def ->
            val new = drafts.getValue(def.name)
            if (def.dataType == SettingDataType.PASSWORD) {
                ChangeLine(def.label, if (def.value.isEmpty()) "not set" else "******", "new password")
            } else {
                ChangeLine(def.label, display(def, def.value), display(def, new))
            }
        }

    fun display(def: SettingDefinition, value: String): String = when (def.dataType) {
        SettingDataType.BOOL -> if (value == "Y") "On" else "Off"
        SettingDataType.ENUM -> def.options.firstOrNull { it.first == value }?.second ?: value
        SettingDataType.SLABS -> RateSlabs.parse(value)?.let { RateSlabs.describe(it, "₹") } ?: "(not complete)"
        else -> value.ifEmpty { "(empty)" }
    }

    /** "Allowed: 0 – 28", "At least 1", or null. */
    fun rangeHint(def: SettingDefinition): String? {
        fun plain(d: Double) = d.toBigDecimal().stripTrailingZeros().toPlainString()
        return when {
            def.minValue != null && def.maxValue != null -> "Allowed: ${plain(def.minValue)} – ${plain(def.maxValue)}"
            def.minValue != null -> "At least ${plain(def.minValue)}"
            def.maxValue != null -> "At most ${plain(def.maxValue)}"
            else -> null
        }
    }

    private fun isEnabled(def: SettingDefinition, byName: Map<String, SettingDefinition>, drafts: Map<String, String>): Boolean {
        var parent = def.dependsOn
        val seen = mutableSetOf(def.name)
        while (parent != null && seen.add(parent)) {
            val parentDef = byName[parent] ?: return true
            if ((drafts[parent] ?: parentDef.value) != "Y") return false
            parent = parentDef.dependsOn
        }
        return true
    }

    private fun dependentsOf(name: String, definitions: List<SettingDefinition>): Set<String> {
        val result = mutableSetOf<String>()
        var frontier = setOf(name)
        while (frontier.isNotEmpty()) {
            frontier = definitions.filter { it.dependsOn in frontier && result.add(it.name) }.map { it.name }.toSet()
        }
        return result
    }
}
