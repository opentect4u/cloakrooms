package com.synergic.cloakroom.domain.shift

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class ShiftResolverTest {

    private val seeded = listOf(
        ShiftSlot(1, "06:00", "14:00"),
        ShiftSlot(2, "14:00", "22:00"),
        ShiftSlot(3, "22:00", "06:00"),
    )

    private fun at(hhmm: String) = ShiftResolver.shiftAt(seeded, LocalTime.parse(hhmm))

    @Test
    fun `day shifts include the start and exclude the end`() {
        assertEquals(1L, at("06:00"))
        assertEquals(1L, at("13:59"))
        assertEquals(2L, at("14:00"))
        assertEquals(2L, at("21:59"))
    }

    @Test
    fun `night shift crosses midnight`() {
        assertEquals(3L, at("22:00"))
        assertEquals(3L, at("23:59"))
        assertEquals(3L, at("00:00"))
        assertEquals(3L, at("05:59"))
    }

    @Test
    fun `no shift covers the time`() {
        assertNull(ShiftResolver.shiftAt(listOf(ShiftSlot(1, "09:00", "17:00")), LocalTime.of(8, 0)))
        assertNull(ShiftResolver.shiftAt(emptyList(), LocalTime.NOON))
        assertNull(ShiftResolver.shiftAt(listOf(ShiftSlot(1, "bad", "17:00")), LocalTime.NOON))
    }

    private fun within(from: String, to: String, at: String, early: Int = 15) =
        ShiftResolver.isWithin(ShiftSlot(1, from, to), LocalTime.parse(at), early)

    @Test
    fun `login window opens early and closes at the shift end`() {
        assertEquals(false, within("06:00", "14:00", "05:44"))
        assertEquals(true, within("06:00", "14:00", "05:45"))
        assertEquals(true, within("06:00", "14:00", "13:59"))
        assertEquals(false, within("06:00", "14:00", "14:00"))
        assertEquals(false, within("06:00", "14:00", "05:59", early = 0))
    }

    @Test
    fun `login window across midnight`() {
        assertEquals(false, within("22:00", "06:00", "21:44"))
        assertEquals(true, within("22:00", "06:00", "21:45"))
        assertEquals(true, within("22:00", "06:00", "23:59"))
        assertEquals(true, within("22:00", "06:00", "02:00"))
        assertEquals(true, within("22:00", "06:00", "05:59"))
        assertEquals(false, within("22:00", "06:00", "06:00"))
        assertEquals(false, within("22:00", "06:00", "12:00"))
    }

    @Test
    fun `24-hour shift and unreadable times never block`() {
        assertEquals(true, within("06:00", "06:00", "03:00"))
        assertEquals(true, within("bad", "14:00", "20:00"))
    }
}
