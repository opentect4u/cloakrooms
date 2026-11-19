package com.synergic.cloakroom.ui.settings

import com.synergic.cloakroom.data.seed.SeedData
import com.synergic.cloakroom.domain.settings.AdvanceRule
import com.synergic.cloakroom.domain.settings.SettingDataType
import com.synergic.cloakroom.domain.settings.SettingDefinition
import com.synergic.cloakroom.domain.settings.SettingKeys
import com.synergic.cloakroom.domain.slabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Advance hours = end hour of the 1st price range while Advance is on; nobody can type it. */
class AdvanceHoursFormTest {

    private val seeded = SeedData.settings.mapIndexed { i, s ->
        SettingDefinition(
            name = s.name, value = s.value, group = s.type, label = s.label,
            dataType = SettingDataType.valueOf(s.dataType),
            options = SettingDefinition.parseOptions(s.options),
            minValue = s.min, maxValue = s.max, dependsOn = s.dependsOn,
            sortOrder = (i + 1) * 10, description = s.description,
        )
    }

    private fun defs(vararg values: Pair<String, String>): List<SettingDefinition> {
        val map = values.toMap()
        return seeded.map { d -> map[d.name]?.let { d.copy(value = it) } ?: d }
    }

    private fun field(defs: List<SettingDefinition>, drafts: Map<String, String>) =
        SettingsForm.tabs(defs, drafts).flatMap { it.fields }.first { it.def.name == SettingKeys.ADVANCE_HOURS }

    @Test
    fun `hours are the end of the 1st range, one range or more`() {
        assertEquals(24, AdvanceRule.hoursFor(slabs(Triple(0, 24, "30"))))
        assertEquals(24, AdvanceRule.hoursFor(slabs(Triple(0, 24, "30"), Triple(24, 48, "10"))))
        assertEquals(12, AdvanceRule.hoursFor(slabs(Triple(0, 12, "30"), Triple(12, 48, "10"))))
        assertNull(AdvanceRule.hoursFor("not json"))
    }

    @Test
    fun `field is greyed out and read-only while Advance is on`() {
        val d = defs(SettingKeys.ADVANCE_FLAG to "Y")
        val f = field(d, emptyMap())
        assertFalse(f.enabled)
        assertTrue(f.def.readOnly)
        assertEquals("24", f.value)
    }

    @Test
    fun `switching Advance on fills hours from the 1st range`() {
        val d = defs(SettingKeys.ADVANCE_HOURS to "6", SettingKeys.RATE_SLABS to slabs(Triple(0, 24, "30"), Triple(24, 48, "10")))
        val drafts = SettingsForm.edit(d, emptyMap(), SettingKeys.ADVANCE_FLAG, "Y")
        assertEquals("24", drafts[SettingKeys.ADVANCE_HOURS])
        assertTrue(SettingsForm.tabs(d, drafts).all { it.errorCount == 0 })
    }

    @Test
    fun `changing the 1st range moves the hours with it`() {
        val d = defs(SettingKeys.ADVANCE_FLAG to "Y")
        val drafts = SettingsForm.edit(d, emptyMap(), SettingKeys.RATE_SLABS, slabs(Triple(0, 12, "30"), Triple(12, 48, "10")))
        assertEquals("12", drafts[SettingKeys.ADVANCE_HOURS])
        // Back to 0–24: hours equal the saved 24 again, so no change is left.
        val back = SettingsForm.edit(d, drafts, SettingKeys.RATE_SLABS, slabs(Triple(0, 24, "50"), Triple(24, 48, "20")))
        assertNull(back[SettingKeys.ADVANCE_HOURS])
    }

    @Test
    fun `a typed value is replaced`() {
        val d = defs(SettingKeys.ADVANCE_FLAG to "Y")
        val drafts = SettingsForm.edit(d, emptyMap(), SettingKeys.ADVANCE_HOURS, "10")
        assertNull("24 is already saved", drafts[SettingKeys.ADVANCE_HOURS])
    }

    @Test
    fun `an old saved value that does not match is offered for saving when the screen opens`() {
        val d = defs(SettingKeys.ADVANCE_FLAG to "Y", SettingKeys.ADVANCE_HOURS to "6")
        assertEquals(mapOf(SettingKeys.ADVANCE_HOURS to "24"), SettingsForm.initialDrafts(d))
        assertEquals(emptyMap<String, String>(), SettingsForm.initialDrafts(defs(SettingKeys.ADVANCE_HOURS to "6")))
    }

    @Test
    fun `with Advance off nothing is forced`() {
        val d = defs()
        assertEquals(emptyMap<String, String>(), SettingsForm.edit(d, emptyMap(), SettingKeys.RATE_SLABS, slabs(Triple(0, 12, "30"))) - SettingKeys.RATE_SLABS)
        assertFalse(field(d, emptyMap()).def.readOnly)
    }
}
