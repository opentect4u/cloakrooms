package com.synergic.cloakroom.domain.billing

import com.synergic.cloakroom.domain.settings
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.SettingKeys.ADVANCE_FLAG
import com.synergic.cloakroom.domain.settings.SettingKeys.ADVANCE_HOURS
import com.synergic.cloakroom.domain.settings.SettingKeys.GRACE_MINUTES
import com.synergic.cloakroom.domain.settings.SettingKeys.GST_FLAG
import com.synergic.cloakroom.domain.settings.SettingKeys.GST_INCLUSIVE
import com.synergic.cloakroom.domain.settings.SettingKeys.RATE_SLABS
import com.synergic.cloakroom.domain.slabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** Price ranges (API_GUIDE.md §7 slab rule, the owner's examples) and the advance. */
class SlabBillingTest {

    private val checkIn = Instant.parse("2026-11-02T04:30:00Z")
    private fun hours(h: Long, m: Long = 0) = Duration.ofHours(h).plusMinutes(m)
    private fun money(text: String) = BigDecimal(text).money()

    private val only30 = slabs(Triple(0, 24, "30"))
    private val thirtyThenTen = slabs(Triple(0, 24, "30"), Triple(24, 48, "10"))
    private val shortRanges = slabs(Triple(0, 4, "10"), Triple(4, 24, "20"))

    /** GST off, so the amounts are the rent itself, as in the owner's examples. */
    private fun tariff(json: String, vararg more: Pair<String, String>): AppSettings =
        settings(RATE_SLABS to json, GST_FLAG to "N", *more)

    private fun bill(stored: Duration, bags: Int, s: AppSettings, advance: String = "0") =
        BillCalculator.calculate(checkIn, checkIn + stored, bags, BigDecimal.ZERO, s, money(advance))

    // ---- The owner's / backend's examples --------------------------------------------------

    @Test
    fun `0-24 h Rs 30 only, 2 bags, 2 days is Rs 120`() {
        val b = bill(hours(48), 2, tariff(only30))
        assertEquals(money("120"), b.netAmt)
        assertEquals(2, b.blocks)
    }

    @Test
    fun `0-24 h Rs 30 plus 24-48 h Rs 10, 2 bags, 3 days is Rs 100`() {
        val b = bill(hours(72), 2, tariff(thirtyThenTen))
        assertEquals(money("100"), b.netAmt)
        assertEquals(3, b.blocks)
        assertEquals(listOf(1, 2), b.slabCounts)
    }

    @Test
    fun `0-4 h Rs 10 plus 4-24 h Rs 20 - 3 h is 10, 5 h is 30, 25 h is 50`() {
        val s = tariff(shortRanges)
        assertEquals(money("10"), bill(hours(3), 1, s).netAmt)
        assertEquals(money("30"), bill(hours(5), 1, s).netAmt)
        assertEquals(money("50"), bill(hours(25), 1, s).netAmt)
        // The last range (20 h long) repeats every 20 h: 45 h = 10 + 20 + 20 + 20... until 44 h, then one more.
        assertEquals(money("50"), bill(hours(44), 1, s).netAmt)
        assertEquals(money("70"), bill(hours(44, 1), 1, s).netAmt)
    }

    @Test
    fun `exact range ends do not start the next range`() {
        val s = tariff(shortRanges)
        assertEquals(money("10"), bill(hours(4), 1, s).netAmt)
        assertEquals(money("30"), bill(hours(4, 1), 1, s).netAmt)
        assertEquals(money("30"), bill(hours(24), 1, s).netAmt)
    }

    @Test
    fun `the old two-rate tariff gives the same bills as slabs 0-24 Rs 50 plus 24-48 Rs 20`() {
        val s = tariff(slabs(Triple(0, 24, "50"), Triple(24, 48, "20")))
        assertEquals(money("100"), bill(Duration.ofMinutes(2), 2, s).netAmt)
        assertEquals(money("50"), bill(hours(24), 1, s).netAmt)
        assertEquals(money("70"), bill(hours(24, 1), 1, s).netAmt)
        assertEquals(money("270"), bill(hours(50), 3, s).netAmt)
    }

    @Test
    fun `grace minutes are taken off before the ranges`() {
        val s = tariff(thirtyThenTen, GRACE_MINUTES to "15")
        assertEquals(money("30"), bill(hours(24, 10), 1, s).netAmt)
        assertEquals(money("40"), bill(hours(24, 16), 1, s).netAmt)
    }

    @Test
    fun `breakup lists each charged range and the repeats`() {
        val b = bill(hours(72), 2, tariff(thirtyThenTen))
        val lines = b.charges.map { RateSlabs.label(it) to it.times }
        assertEquals(listOf("0-24 h" to 1, "24-48 h" to 1, "Next 1 x 24 h" to 1), lines)
        assertEquals(24, b.blockHours)
        assertEquals(money("30"), b.firstBlockRate)
        assertEquals(money("10"), b.nextBlockRate)
    }

    @Test
    fun `counts can be rebuilt from days_charged for a reprint`() {
        val slabs = RateSlabs.parse(shortRanges)!!
        listOf(1L, 300L, 1500L, 3000L).forEach { m ->
            val counts = RateSlabs.counts(slabs, m)
            assertEquals(counts, RateSlabs.countsFromDays(slabs, counts.sum()))
        }
    }

    // ---- Price list rules (same as the server's /rates) ---------------------------------------

    @Test
    fun `price list must start at 0, have no gaps and at most 20 ranges`() {
        assertNotNull(RateSlabs.parse(thirtyThenTen))
        assertEquals("The first range must start at 0 hours", RateSlabs.draftProblem(slabs(Triple(1, 24, "30"))))
        assertEquals("Range 2 must start at 24 hours", RateSlabs.draftProblem(slabs(Triple(0, 24, "30"), Triple(25, 48, "10"))))
        assertEquals("Range 1: \"to\" hours must be more than 0", RateSlabs.draftProblem(slabs(Triple(0, 0, "30"))))
        assertEquals("Add at least one price range", RateSlabs.draftProblem("[]"))
        val many = (0 until 21).map { Triple(it, it + 1, "1") }.toTypedArray()
        assertEquals("At most 20 price ranges", RateSlabs.draftProblem(slabs(*many)))
        assertEquals("Range 1: enter the price", RateSlabs.draftProblem("""[{"from_hour":0,"to_hour":"24","rate":""}]"""))
        assertEquals("Range 1: at most 2 decimals in the price", RateSlabs.draftProblem("""[{"from_hour":0,"to_hour":"24","rate":"1.005"}]"""))
        assertNull(RateSlabs.parse("not json"))
    }

    @Test
    fun `Add starts where the last range ends, with the same length and price`() {
        val rows = RateSlabs.addRow(RateSlabs.parseDraft(only30)!!)
        assertEquals(RateSlabs.DraftRow("24", "48", "30.00"), rows.last())
        // Removing a middle row keeps the list joined up.
        val chained = RateSlabs.chain(listOf(RateSlabs.DraftRow("0", "4", "10"), RateSlabs.DraftRow("9", "24", "20")))
        assertEquals("4", chained[1].from)
    }

    // ---- Advance -----------------------------------------------------------------------------

    @Test
    fun `advance is the bill for ADVANCE_HOURS - the owner's example`() {
        // Rates 20 / 10, 2 bags, advance 48 h: 2 x 20 + 2 x 10 = 60.
        val s = tariff(slabs(Triple(0, 24, "20"), Triple(24, 48, "10")), ADVANCE_FLAG to "Y", ADVANCE_HOURS to "48")
        val advance = BillCalculator.advance(checkIn, 2, s)
        assertEquals(money("60"), advance)
        // Out on day 3: bill 2 x (20 + 10 + 10) = 80, balance 20.
        val b = bill(hours(60), 2, s, advance = "60")
        assertEquals(money("80"), b.netAmt)
        assertEquals(money("20"), b.balAmt)
    }

    @Test
    fun `advance of 24 h with 0-24 Rs 30 plus 24-48 Rs 10`() {
        val s = tariff(thirtyThenTen, ADVANCE_FLAG to "Y", ADVANCE_HOURS to "24", GRACE_MINUTES to "30")
        assertEquals(money("60"), BillCalculator.advance(checkIn, 2, s))
    }

    @Test
    fun `no refund when the advance is more than the bill`() {
        val s = tariff(thirtyThenTen)
        val b = bill(hours(2), 1, s, advance = "40")
        assertEquals(money("30"), b.netAmt)
        assertEquals(money("0"), b.balAmt)
    }

    @Test
    fun `advance off is zero`() {
        assertEquals(money("0"), BillCalculator.advance(checkIn, 2, tariff(thirtyThenTen, ADVANCE_FLAG to "N")))
    }

    @Test
    fun `advance follows GST added on top`() {
        val s = settings(RATE_SLABS to only30, GST_INCLUSIVE to "N", ADVANCE_FLAG to "Y", ADVANCE_HOURS to "24")
        // 30 + 18 % = 35.40 per bag.
        assertEquals(money("35.40"), BillCalculator.advance(checkIn, 1, s))
    }

    @Test
    fun `an invalid price list cannot be billed`() {
        val s = settings().copy(rateSlabs = listOf(RateSlab(5, 24, BigDecimal.TEN)))
        assertThrows(BillCalculationException::class.java) { bill(hours(1), 1, s) }
    }
}
