package com.synergic.cloakroom.domain

import com.synergic.cloakroom.data.seed.SeedData
import com.synergic.cloakroom.domain.settings.AppSettings

/** The seeded settings, with some values replaced: `settings("GST_FLAG" to "N")`. */
fun settings(vararg overrides: Pair<String, String>): AppSettings =
    AppSettings.fromValues(SeedData.settings.associate { it.name to it.value } + overrides)
