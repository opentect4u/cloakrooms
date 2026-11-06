package com.synergic.cloakroom.domain.time

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class DateTimesTest {

    @Test
    fun `db text is UTC and round-trips`() {
        val instant = Instant.parse("2026-11-02T08:05:09Z")
        assertEquals("2026-11-02 08:05:09", DateTimes.toDbText(instant))
        assertEquals(instant, DateTimes.fromDbText("2026-11-02 08:05:09"))
    }

    @Test
    fun `local date follows Asia Kolkata`() {
        // 19:00 UTC on 1 Nov is 00:30 IST on 2 Nov.
        assertEquals("2026-11-02", DateTimes.localDateText(Instant.parse("2026-11-01T19:00:00Z")))
        assertEquals("2026-11-01", DateTimes.localDateText(Instant.parse("2026-11-01T18:29:59Z")))
    }

    @Test
    fun `display text is shown in IST`() {
        assertEquals("02-11-2026 01:35 PM", DateTimes.displayText("2026-11-02 08:05:00"))
    }
}
