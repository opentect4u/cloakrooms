package com.synergic.cloakroom.ui.settings

import com.synergic.cloakroom.data.seed.SeedData
import com.synergic.cloakroom.domain.settings.SettingDataType
import com.synergic.cloakroom.domain.settings.SettingDefinition
import com.synergic.cloakroom.domain.settings.SettingKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFormTest {

    /** The seeded md_setting rows, as the repository hands them to the screen. */
    private val defs = SeedData.settings.mapIndexed { i, s ->
        SettingDefinition(
            name = s.name, value = s.value, group = s.type, label = s.label,
            dataType = SettingDataType.valueOf(s.dataType),
            options = SettingDefinition.parseOptions(s.options),
            minValue = s.min, maxValue = s.max, dependsOn = s.dependsOn,
            sortOrder = (i + 1) * 10, description = s.description,
        )
    }

    private fun field(tabs: List<SettingsTab>, name: String) = tabs.flatMap { it.fields }.first { it.def.name == name }

    @Test
    fun `tabs are Billing, General and Print, holding every row`() {
        val tabs = SettingsForm.tabs(defs, emptyMap())
        assertEquals(listOf("Billing", "General", "Print"), tabs.map { it.title })
        assertEquals(defs.size - SettingsForm.SHOWN_ELSEWHERE.size, tabs.sumOf { it.fields.size })
        assertEquals(SettingKeys.RATE_SLABS, tabs[0].fields.first().def.name)
        assertTrue(tabs.all { it.errorCount == 0 })
    }

    @Test
    fun `printer type and Bluetooth printer are left to Printer setup`() {
        val names = SettingsForm.tabs(defs, emptyMap()).flatMap { it.fields }.map { it.def.name }
        assertFalse(SettingKeys.PRINTER_TYPE in names)
        assertFalse(SettingKeys.BT_PRINTER in names)
        assertTrue(SettingKeys.PAPER_WIDTH in names)
    }

    @Test
    fun `a new row in an unknown group gets its own tab with no code change`() {
        val extra = defs + defs.first().copy(name = "NEW_ONE", group = "X", label = "New one")
        val tabs = SettingsForm.tabs(extra, emptyMap())
        assertEquals("X", tabs.last().title)
        assertEquals("NEW_ONE", tabs.last().fields.single().def.name)
    }

    @Test
    fun `fields follow sort_order`() {
        val reordered = defs.map { if (it.name == SettingKeys.GST_FLAG) it.copy(sortOrder = 1) else it }
        assertEquals(SettingKeys.GST_FLAG, SettingsForm.tabs(reordered, emptyMap())[0].fields.first().def.name)
    }

    @Test
    fun `depends_on greys out a field while the switch is off`() {
        assertTrue(field(SettingsForm.tabs(defs, emptyMap()), SettingKeys.GST_PCT).enabled)
        val drafts = SettingsForm.edit(defs, emptyMap(), SettingKeys.GST_FLAG, "N")
        val gstPct = field(SettingsForm.tabs(defs, drafts), SettingKeys.GST_PCT)
        assertFalse(gstPct.enabled)
        assertEquals("GST applicable", gstPct.parentLabel)
        assertFalse(field(SettingsForm.tabs(defs, emptyMap()), SettingKeys.REPORT_PWD).enabled)
    }

    @Test
    fun `switching a parent off drops the drafts of its dependents`() {
        var drafts = SettingsForm.edit(defs, emptyMap(), SettingKeys.GST_PCT, "12")
        drafts = SettingsForm.edit(defs, drafts, SettingKeys.GST_FLAG, "N")
        assertEquals(mapOf(SettingKeys.GST_FLAG to "N"), drafts)
    }

    @Test
    fun `editing back to the saved value is not a change`() {
        var drafts = SettingsForm.edit(defs, emptyMap(), SettingKeys.GRACE_MINUTES, "12")
        assertTrue(field(SettingsForm.tabs(defs, drafts), SettingKeys.GRACE_MINUTES).changed)
        drafts = SettingsForm.edit(defs, drafts, SettingKeys.GRACE_MINUTES, "0")
        assertTrue(drafts.isEmpty())
        assertTrue(SettingsForm.edit(defs, emptyMap(), SettingKeys.REPORT_PWD, "").isEmpty())
    }

    @Test
    fun `invalid draft shows its error on the field and the tab`() {
        val drafts = SettingsForm.edit(defs, emptyMap(), SettingKeys.CHECKIN_PRINT_COPIES, "5")
        val tabs = SettingsForm.tabs(defs, drafts)
        assertEquals("Must be at most 3", field(tabs, SettingKeys.CHECKIN_PRINT_COPIES).error)
        assertEquals(1, tabs.first { it.title == "Print" }.errorCount)
        assertNull(field(tabs, SettingKeys.QR_FLAG).error)
    }

    @Test
    fun `server errors are shown on the field`() {
        val tabs = SettingsForm.tabs(defs, emptyMap(), mapOf(SettingKeys.QR_FLAG to "Refused"))
        assertEquals("Refused", field(tabs, SettingKeys.QR_FLAG).error)
    }

    @Test
    fun `change lines use friendly values`() {
        var drafts = SettingsForm.edit(defs, emptyMap(), SettingKeys.GST_TYPE, "I")
        drafts = SettingsForm.edit(defs, drafts, SettingKeys.QR_FLAG, "N")
        drafts = SettingsForm.edit(defs, drafts, SettingKeys.REPORT_PWD_FLAG, "Y")
        drafts = SettingsForm.edit(defs, drafts, SettingKeys.REPORT_PWD, "secret")
        val lines = SettingsForm.changeLines(defs, drafts).associateBy { it.label }
        assertEquals(ChangeLine("GST type", "CGST+SGST", "IGST"), lines["GST type"])
        assertEquals(ChangeLine("Print QR", "On", "Off"), lines["Print QR"])
        assertEquals(ChangeLine("Report password value", "not set", "new password"), lines["Report password value"])
        assertFalse(lines.values.any { it.to.contains("secret") })
    }

    @Test
    fun `range hint describes min and max`() {
        assertEquals("Allowed: 0 – 28", SettingsForm.rangeHint(defs.first { it.name == SettingKeys.GST_PCT }))
        assertEquals("At least 15", SettingsForm.rangeHint(defs.first { it.name == SettingKeys.SYNC_INTERVAL_MIN }))
        assertNull(SettingsForm.rangeHint(defs.first { it.name == SettingKeys.GST_FLAG }))
    }
}
