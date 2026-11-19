package com.synergic.cloakroom.domain.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SubscriptionTest {
    private val end = LocalDate.parse("2026-11-18")
    private fun on(day: String) = Subscription.status("2026-11-18", LocalDate.parse(day))

    @Test
    fun `active more than 10 days before the end date`() {
        assertEquals(SubscriptionStatus.Active, on("2026-11-08"))
    }

    @Test
    fun `notice for the last 10 days, end date included`() {
        assertEquals(SubscriptionStatus.ExpiringSoon(end, 9), on("2026-11-09"))
        assertEquals(SubscriptionStatus.ExpiringSoon(end, 1), on("2026-11-17"))
        assertEquals(SubscriptionStatus.ExpiringSoon(end, 0), on("2026-11-18"))
    }

    @Test
    fun `expired from the next day`() {
        assertEquals(SubscriptionStatus.Expired(end), on("2026-11-19"))
        assertEquals(SubscriptionStatus.Expired(end), on("2027-01-01"))
    }

    @Test
    fun `no or unreadable renewal date is active`() {
        val today = LocalDate.parse("2026-11-19")
        assertEquals(SubscriptionStatus.Active, Subscription.status(null, today))
        assertEquals(SubscriptionStatus.Active, Subscription.status("", today))
        assertEquals(SubscriptionStatus.Active, Subscription.status("not a date", today))
    }

    @Test
    fun `server date formats`() {
        assertEquals(end, Subscription.parse("2026-11-18"))
        assertEquals(end, Subscription.parse("2026-11-18 00:00:00"))
        // Midnight IST sent in UTC.
        assertEquals(end, Subscription.parse("2026-11-17T18:30:00.000Z"))
    }

    @Test
    fun `notice texts`() {
        assertNull(Subscription.notice(SubscriptionStatus.Active))
        assertTrue(Subscription.notice(SubscriptionStatus.Expired(end))!!.contains("expired on 18-11-2026"))
        assertTrue(Subscription.notice(SubscriptionStatus.ExpiringSoon(end, 0))!!.contains("ends today"))
        assertTrue(Subscription.notice(SubscriptionStatus.ExpiringSoon(end, 5))!!.contains("5 days left"))
    }
}
