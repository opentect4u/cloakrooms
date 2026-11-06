package com.synergic.cloakroom.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsResolverTest {

    private data class Row(val name: String, val value: String, val userId: Long?)

    private fun resolve(rows: List<Row>, userId: Long?) =
        SettingsResolver.resolve(rows, userId, { it.name }, { it.userId }).mapValues { it.value.value }

    @Test
    fun `customer row is used when the user has no own row`() {
        val rows = listOf(Row("REPORT_FLAG", "Y", null))
        assertEquals(mapOf("REPORT_FLAG" to "Y"), resolve(rows, userId = 2))
    }

    @Test
    fun `user row overrides customer row`() {
        val rows = listOf(Row("REPORT_FLAG", "Y", null), Row("REPORT_FLAG", "N", 2))
        assertEquals("N", resolve(rows, userId = 2)["REPORT_FLAG"])
    }

    @Test
    fun `user row wins regardless of row order`() {
        val rows = listOf(Row("REPORT_FLAG", "N", 2), Row("REPORT_FLAG", "Y", null))
        assertEquals("N", resolve(rows, userId = 2)["REPORT_FLAG"])
    }

    @Test
    fun `rows of other users are ignored`() {
        val rows = listOf(Row("REPORT_FLAG", "Y", null), Row("REPORT_FLAG", "N", 3))
        assertEquals("Y", resolve(rows, userId = 2)["REPORT_FLAG"])
    }

    @Test
    fun `without a user only customer rows count`() {
        val rows = listOf(Row("REPORT_FLAG", "Y", null), Row("REPORT_FLAG", "N", 2))
        assertEquals("Y", resolve(rows, userId = null)["REPORT_FLAG"])
    }

    @Test
    fun `user-only setting is visible to that user`() {
        val rows = listOf(Row("PRINT_COPIES", "2", 2))
        assertEquals(mapOf("PRINT_COPIES" to "2"), resolve(rows, userId = 2))
        assertEquals(emptyMap<String, String>(), resolve(rows, userId = null))
    }
}
