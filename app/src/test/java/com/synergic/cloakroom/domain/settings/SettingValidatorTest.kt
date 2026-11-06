package com.synergic.cloakroom.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingValidatorTest {

    private fun def(
        name: String,
        type: SettingDataType,
        value: String = "",
        min: Double? = null,
        max: Double? = null,
        options: String? = null,
        dependsOn: String? = null,
    ) = SettingDefinition(
        name = name, value = value, group = "B", label = name, dataType = type,
        options = SettingDefinition.parseOptions(options), minValue = min, maxValue = max,
        dependsOn = dependsOn, sortOrder = 0, description = null,
    )

    @Test
    fun `numbers respect type and range`() {
        val pct = def("GST_PCT", SettingDataType.DECIMAL, min = 0.0, max = 28.0)
        assertNull(SettingValidator.validateValue(pct, "18"))
        assertNull(SettingValidator.validateValue(pct, "28"))
        assertNull(SettingValidator.validateValue(pct, "12.5"))
        assertEquals("Must be at most 28", SettingValidator.validateValue(pct, "28.01"))
        assertEquals("Must be at least 0", SettingValidator.validateValue(pct, "-1"))
        assertEquals("Enter a number", SettingValidator.validateValue(pct, "abc"))

        val copies = def("PRINT_COPIES", SettingDataType.INT, min = 1.0, max = 3.0)
        assertNull(SettingValidator.validateValue(copies, "3"))
        assertEquals("Enter a whole number", SettingValidator.validateValue(copies, "2.5"))
        assertEquals("Must be at most 3", SettingValidator.validateValue(copies, "4"))
    }

    @Test
    fun `bool and enum values`() {
        assertNull(SettingValidator.validateValue(def("F", SettingDataType.BOOL), "Y"))
        assertEquals("Must be Y or N", SettingValidator.validateValue(def("F", SettingDataType.BOOL), "y"))

        val mode = def("DEFAULT_PAY_MODE", SettingDataType.ENUM, options = "C:Cash|U:UPI")
        assertNull(SettingValidator.validateValue(mode, "U"))
        assertEquals("Choose one of: Cash, UPI", SettingValidator.validateValue(mode, "X"))
    }

    @Test
    fun `options parse into code and label`() {
        assertEquals(
            listOf("D" to "Daily", "Y" to "Financial year (Apr–Mar)"),
            SettingDefinition.parseOptions("D:Daily|Y:Financial year (Apr–Mar)"),
        )
        assertEquals(emptyList<Pair<String, String>>(), SettingDefinition.parseOptions(null))
    }

    @Test
    fun `depends_on uses the value after the batch`() {
        val defs = listOf(
            def("GST_FLAG", SettingDataType.BOOL, value = "N"),
            def("GST_PCT", SettingDataType.DECIMAL, value = "18", min = 0.0, max = 28.0, dependsOn = "GST_FLAG"),
        )
        assertEquals(setOf("GST_PCT"), SettingValidator.validateChanges(defs, mapOf("GST_PCT" to "12")).keys)
        assertTrue(SettingValidator.validateChanges(defs, mapOf("GST_FLAG" to "Y", "GST_PCT" to "12")).isEmpty())
    }

    @Test
    fun `unknown setting is refused`() {
        assertEquals("Unknown setting", SettingValidator.validateChanges(emptyList(), mapOf("NOPE" to "1"))["NOPE"])
    }
}
