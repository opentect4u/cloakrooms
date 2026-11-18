package com.synergic.cloakroom.domain.time

import org.junit.Assert.assertEquals
import org.junit.Test

/** Timestamps from the server are IST (since 2026-11-18); the phone keeps UTC. */
class DateTimesServerTest {

    @Test
    fun `IST wall time becomes UTC`() {
        assertEquals("2026-11-18 10:01:46", DateTimes.fromServerText("2026-11-18 15:31:46"))
        assertEquals("2026-11-17 23:30:00", DateTimes.fromServerText("2026-11-18 05:00:00"))
        assertEquals("ISO without zone, with milliseconds", "2026-11-18 10:01:46", DateTimes.fromServerText("2026-11-18T15:31:46.000"))
    }

    @Test
    fun `a time with an explicit zone keeps it`() {
        assertEquals("2026-11-18 10:01:46", DateTimes.fromServerText("2026-11-18T10:01:46Z"))
        assertEquals("2026-11-18 10:01:46", DateTimes.fromServerText("2026-11-18T15:31:46+05:30"))
        assertEquals("2026-11-18 10:01:46", DateTimes.fromServerText("2026-11-18 15:31:46+05:30"))
    }

    @Test
    fun `shown on screen as the same IST time the server sent`() {
        assertEquals("18-11-2026 03:31 PM", DateTimes.displayText(DateTimes.fromServerText("2026-11-18 15:31:46")).uppercase())
    }

    @Test
    fun `unreadable text is kept`() {
        assertEquals("yesterday", DateTimes.fromServerText("yesterday"))
    }
}
