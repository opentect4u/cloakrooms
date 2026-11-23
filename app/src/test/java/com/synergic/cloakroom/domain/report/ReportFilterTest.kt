package com.synergic.cloakroom.domain.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ReportFilterTest {

    private val day = LocalDate.of(2026, 11, 23)

    @Test
    fun `default times cover whole days`() {
        val f = ReportFilter(day, day, null)
        assertTrue(f.wholeDays)
        // 23:59:30 IST = 18:29:30 UTC is still inside the day.
        assertTrue(f.containsDbTime("2026-11-23 18:29:30"))
    }

    @Test
    fun `times limit the period, both ends included to the minute`() {
        val f = ReportFilter(day, day, null, fromTime = LocalTime.of(10, 0), toTime = LocalTime.of(14, 30))
        assertFalse(f.wholeDays)
        // DB text is UTC; IST = UTC + 05:30.
        assertFalse(f.containsDbTime("2026-11-23 04:29:59")) // 09:59:59 IST
        assertTrue(f.containsDbTime("2026-11-23 04:30:00"))  // 10:00:00 IST
        assertTrue(f.containsDbTime("2026-11-23 09:00:59"))  // 14:30:59 IST
        assertFalse(f.containsDbTime("2026-11-23 09:01:00")) // 14:31:00 IST
    }

    @Test
    fun `ends picked the wrong way round are swapped`() {
        val f = ReportFilter(day, day, null, fromTime = LocalTime.of(18, 0), toTime = LocalTime.of(9, 0)).ordered()
        assertEquals(LocalTime.of(9, 0), f.fromTime)
        assertEquals(LocalTime.of(18, 0), f.toTime)
    }

    @Test
    fun `dates in the wrong order keep whole days`() {
        val f = ReportFilter(day, day.minusDays(3), null).ordered()
        assertEquals(day.minusDays(3), f.from)
        assertEquals(day, f.to)
        assertTrue(f.wholeDays)
    }

    @Test
    fun `rows outside the times are dropped`() {
        val f = ReportFilter(day, day, null, fromTime = LocalTime.of(10, 0), toTime = LocalTime.of(11, 0))
        fun row(utc: String) = CheckinRow("t$utc", "R", utc, "U01", "A", "9999999999", "P", 1, false)
        val kept = f.keepInPeriod(listOf(row("2026-11-23 04:00:00"), row("2026-11-23 05:00:00"), row("2026-11-23 06:00:00")))
        assertEquals(listOf("2026-11-23 05:00:00"), kept.map { it.dateTimeIn })
    }
}
