package com.synergic.cloakroom.domain

import com.synergic.cloakroom.data.seed.SeedData
import com.synergic.cloakroom.domain.billing.RateSlab
import com.synergic.cloakroom.domain.billing.RateSlabs
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.SettingKeys

/**
 * The seeded settings, with some values replaced: `settings("GST_FLAG" to "N")`.
 * The retired two-rate keys (FIRST_BLOCK_RATE, NEXT_BLOCK_RATE, BLOCK_HOURS; defaults 50 / 20 / 24)
 * are still accepted and become the price ranges 0–B h first rate + B–2B h next rate, so the
 * older bill tests check that the slab formula gives the same bills.
 */
fun settings(vararg overrides: Pair<String, String>): AppSettings {
    val map = overrides.toMap()
    val legacy = listOf(SettingKeys.FIRST_BLOCK_RATE, SettingKeys.NEXT_BLOCK_RATE, SettingKeys.BLOCK_HOURS)
    val slabs = if (legacy.any { it in map }) {
        mapOf(
            SettingKeys.RATE_SLABS to RateSlabs.toJson(
                RateSlabs.fromTwoRate(
                    (map[SettingKeys.BLOCK_HOURS] ?: "24").toInt(),
                    (map[SettingKeys.FIRST_BLOCK_RATE] ?: "50").toBigDecimal(),
                    (map[SettingKeys.NEXT_BLOCK_RATE] ?: "20").toBigDecimal(),
                )
            )
        )
    } else {
        emptyMap()
    }
    return AppSettings.fromValues(SeedData.settings.associate { it.name to it.value } + (map - legacy.toSet()) + slabs)
}

/** RATE_SLABS JSON from (from, to, rate) triples: `slabs(Triple(0, 24, "30"))`. */
fun slabs(vararg ranges: Triple<Int, Int, String>): String =
    RateSlabs.toJson(ranges.map { (from, to, rate) -> RateSlab(from, to, rate.toBigDecimal()) })
