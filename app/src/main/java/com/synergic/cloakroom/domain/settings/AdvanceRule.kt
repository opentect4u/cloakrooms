package com.synergic.cloakroom.domain.settings

import com.synergic.cloakroom.domain.billing.RateSlabs

/**
 * ADVANCE_HOURS is not typed by anyone (your rule, 2026-11-19): while Advance is on it always
 * equals the end hour of the 1st price range (0–24 → 24), so the advance is exactly the 1st
 * range's price per bag. The Settings screen greys the field out, and both save paths (station
 * admin, super admin) apply this before saving, so a typed value can never get through.
 */
object AdvanceRule {

    /** End hour of the 1st price range, or null when the ranges are not complete yet. */
    fun hoursFor(rateSlabs: String?): Int? = RateSlabs.parse(rateSlabs)?.firstOrNull()?.toHour

    /**
     * [changes] with ADVANCE_HOURS set from the 1st price range (both as they will be after the
     * save). It is left out when it already has that value, when Advance is off, or when the
     * ranges are not valid (their own error is shown).
     */
    fun apply(definitions: List<SettingDefinition>, changes: Map<String, String>): Map<String, String> {
        val byName = definitions.associateBy { it.name }
        val hoursDef = byName[SettingKeys.ADVANCE_HOURS] ?: return changes
        fun effective(name: String) = changes[name] ?: byName[name]?.value
        val result = changes - SettingKeys.ADVANCE_HOURS
        if (effective(SettingKeys.ADVANCE_FLAG) != "Y") return result
        val hours = hoursFor(effective(SettingKeys.RATE_SLABS))?.toString() ?: return result
        return if (hours == hoursDef.value) result else result + (SettingKeys.ADVANCE_HOURS to hours)
    }

    /** Help text under the greyed-out field. */
    const val DESCRIPTION = "Set automatically: the end hour of the 1st price range"
}
