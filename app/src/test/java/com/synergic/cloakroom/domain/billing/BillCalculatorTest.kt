package com.synergic.cloakroom.domain.billing

import com.synergic.cloakroom.domain.settings
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.GstType
import com.synergic.cloakroom.domain.settings.SettingKeys.BLOCK_HOURS
import com.synergic.cloakroom.domain.settings.SettingKeys.FIRST_BLOCK_RATE
import com.synergic.cloakroom.domain.settings.SettingKeys.GRACE_MINUTES
import com.synergic.cloakroom.domain.settings.SettingKeys.GST_FLAG
import com.synergic.cloakroom.domain.settings.SettingKeys.GST_INCLUSIVE
import com.synergic.cloakroom.domain.settings.SettingKeys.GST_PCT
import com.synergic.cloakroom.domain.settings.SettingKeys.GST_TYPE
import com.synergic.cloakroom.domain.settings.SettingKeys.NEXT_BLOCK_RATE
import com.synergic.cloakroom.domain.settings.SettingKeys.PENALTY_FLAG
import com.synergic.cloakroom.domain.settings.SettingKeys.PENALTY_GST_FLAG
import com.synergic.cloakroom.domain.settings.SettingKeys.PENALTY_MAX_AMT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class BillCalculatorTest {

    private val checkIn = Instant.parse("2026-11-02T04:30:00Z")

    private fun bill(
        stored: Duration,
        bags: Int = 1,
        penalty: String = "0",
        s: AppSettings = settings(),
    ) = BillCalculator.calculate(checkIn, checkIn + stored, bags, BigDecimal(penalty), s)

    private fun assertMoney(expected: String, actual: BigDecimal) = assertEquals(BigDecimal(expected), actual)

    private fun hours(h: Long, m: Long = 0) = Duration.ofHours(h).plusMinutes(m)

    // ---- Examples of API_GUIDE.md §7 (the server checks every bill with them) ----

    private val onTop = arrayOf(GST_INCLUSIVE to "N")

    @Test
    fun `server example - 24 h 10 m with 15 grace minutes is 1 block`() {
        val b = bill(hours(24, 10), s = settings(GRACE_MINUTES to "15"))
        assertEquals(1, b.blocks)
        assertMoney("50.00", b.netAmt)
    }

    @Test
    fun `server example - GST on top of 50`() {
        val b = bill(hours(1), s = settings(*onTop))
        assertMoney("50.00", b.taxableAmt); assertMoney("4.50", b.cgst); assertMoney("4.50", b.sgst); assertMoney("59.00", b.netAmt)
    }

    @Test
    fun `server example - GST on top with a 10 penalty`() {
        val b = bill(hours(1), penalty = "10", s = settings(*onTop))
        assertMoney("60.00", b.taxableAmt); assertMoney("10.80", b.cgst + b.sgst); assertMoney("70.80", b.netAmt)
    }

    @Test
    fun `server example - GST on top, penalty without GST`() {
        val b = bill(hours(1), penalty = "10", s = settings(*onTop, PENALTY_GST_FLAG to "N"))
        assertMoney("50.00", b.taxableAmt); assertMoney("9.00", b.cgst + b.sgst); assertMoney("69.00", b.netAmt)
    }

    @Test
    fun `server example - inclusive 70 plus 10 penalty without GST`() {
        val b = bill(hours(24, 1), penalty = "10", s = settings(PENALTY_GST_FLAG to "N"))
        assertMoney("59.32", b.taxableAmt); assertMoney("5.34", b.cgst); assertMoney("5.34", b.sgst); assertMoney("80.00", b.netAmt)
    }

    @Test
    fun `server rounding check - 50_05 with GST on top gives the odd paisa to CGST`() {
        val b = bill(hours(1), s = settings(*onTop, FIRST_BLOCK_RATE to "50.05"))
        assertMoney("50.05", b.taxableAmt); assertMoney("4.51", b.cgst); assertMoney("4.50", b.sgst); assertMoney("59.06", b.netAmt)
    }

    @Test
    fun `server example - GST off means 0 percent and taxable equals net`() {
        val b = bill(hours(1), s = settings(GST_FLAG to "N"))
        assertMoney("0.00", b.gstPct); assertMoney("50.00", b.taxableAmt); assertMoney("50.00", b.netAmt)
    }

    // ---- The four rows of the table on PDF page 5 (₹50 first 24 h, ₹20 each further 24 h) ----

    @Test
    fun `2 minutes with 2 bags is 100`() {
        val b = bill(Duration.ofMinutes(2), bags = 2)
        assertEquals(1, b.blocks)
        assertMoney("100.00", b.netAmt)
    }

    @Test
    fun `exactly 24 h 00 m is 50`() {
        val b = bill(hours(24))
        assertEquals(1, b.blocks)
        assertMoney("50.00", b.netAmt)
    }

    @Test
    fun `24 h 01 m is 70`() {
        val b = bill(hours(24, 1))
        assertEquals(2, b.blocks)
        assertMoney("70.00", b.netAmt)
    }

    @Test
    fun `50 h with 3 bags is 270`() {
        val b = bill(hours(50), bags = 3)
        assertEquals(3, b.blocks)
        assertMoney("270.00", b.baseAmt)
        assertMoney("270.00", b.netAmt)
    }

    // ---- GST ---------------------------------------------------------------------------------

    @Test
    fun `70 at 18 percent inclusive is 59_32 + 5_34 + 5_34`() {
        val b = bill(hours(24, 1))
        assertMoney("70.00", b.netAmt)
        assertMoney("59.32", b.taxableAmt)
        assertMoney("5.34", b.cgst)
        assertMoney("5.34", b.sgst)
        assertMoney("0.00", b.igst)
        assertMoney("18.00", b.gstPct)
        assertEquals(b.netAmt, b.taxableAmt + b.cgst + b.sgst)
    }

    @Test
    fun `GST off has no tax and the bill is base plus penalty`() {
        val b = bill(hours(24, 1), penalty = "30", s = settings(GST_FLAG to "N"))
        assertFalse(b.gstFlag)
        assertMoney("0.00", b.gstPct)
        assertMoney("0.00", b.cgst)
        assertMoney("0.00", b.sgst)
        assertMoney("0.00", b.igst)
        assertMoney("100.00", b.netAmt)
        assertMoney("100.00", b.taxableAmt)
    }

    @Test
    fun `GST exclusive is added on top`() {
        val b = bill(hours(24, 1), s = settings(GST_INCLUSIVE to "N"))
        assertMoney("70.00", b.taxableAmt)
        assertMoney("6.30", b.cgst)
        assertMoney("6.30", b.sgst)
        assertMoney("82.60", b.netAmt)
    }

    @Test
    fun `IGST takes the whole GST`() {
        val b = bill(hours(24, 1), s = settings(GST_TYPE to "I"))
        assertEquals(GstType.IGST, b.gstType)
        assertMoney("59.32", b.taxableAmt)
        assertMoney("10.68", b.igst)
        assertMoney("0.00", b.cgst)
        assertMoney("0.00", b.sgst)
        assertMoney("70.00", b.netAmt)
    }

    @Test
    fun `odd GST splits so that CGST plus SGST is exact`() {
        // ₹55 at 12% inclusive: taxable 49.11, GST 5.89 → CGST 2.95 (5.89 / 2 rounded up), SGST 2.94.
        val b = bill(hours(1), s = settings(FIRST_BLOCK_RATE to "55", GST_PCT to "12"))
        assertMoney("49.11", b.taxableAmt)
        assertMoney("2.95", b.cgst)
        assertMoney("2.94", b.sgst)
        assertEquals(b.netAmt, b.taxableAmt + b.cgst + b.sgst)
    }

    // ---- Grace and block length --------------------------------------------------------------

    @Test
    fun `grace minutes delay the next block`() {
        val s = settings(GRACE_MINUTES to "30")
        assertEquals(1, bill(hours(24, 30), s = s).blocks)
        assertEquals(2, bill(hours(24, 31), s = s).blocks)
        assertMoney("50.00", bill(hours(24, 30), s = s).netAmt)
    }

    @Test
    fun `grace longer than the stay still charges one block`() {
        val b = bill(Duration.ofMinutes(5), s = settings(GRACE_MINUTES to "60"))
        assertEquals(0, b.minutesCharged)
        assertEquals(1, b.blocks)
    }

    @Test
    fun `block length and rates come from settings`() {
        val s = settings(BLOCK_HOURS to "12", FIRST_BLOCK_RATE to "40", NEXT_BLOCK_RATE to "15")
        val b = bill(hours(25), bags = 2, s = s)
        assertEquals(3, b.blocks)
        assertMoney("140.00", b.baseAmt) // 2 × (40 + 15 × 2)
        assertEquals(12, b.blockHours)
        assertMoney("40.00", b.firstBlockRate)
        assertMoney("15.00", b.nextBlockRate)
    }

    @Test
    fun `seconds are ignored and a clock running backwards counts as zero`() {
        assertEquals(1, bill(hours(24).plusSeconds(59)).blocks)
        assertEquals(1, BillCalculator.calculate(checkIn, checkIn.minusSeconds(600), 1, BigDecimal.ZERO, settings()).blocks)
    }

    // ---- Penalty -----------------------------------------------------------------------------

    @Test
    fun `penalty above the maximum is rejected`() {
        val e = assertThrows(BillCalculationException::class.java) { bill(hours(1), penalty = "500.01") }
        assertEquals("Penalty cannot be more than ₹500.00.", e.message)
        assertMoney("550.00", bill(hours(1), penalty = "500").netAmt)
    }

    @Test
    fun `penalty is rejected when penalties are switched off`() {
        assertThrows(BillCalculationException::class.java) {
            bill(hours(1), penalty = "10", s = settings(PENALTY_FLAG to "N"))
        }
        assertMoney("0.00", bill(hours(1), s = settings(PENALTY_FLAG to "N")).penaltyAmt)
    }

    @Test
    fun `negative penalty is rejected`() {
        assertThrows(BillCalculationException::class.java) { bill(hours(1), penalty = "-1") }
    }

    @Test
    fun `penalty with GST is part of the taxable value`() {
        // 70 + 48 = 118 inclusive of 18% → taxable 100.00, GST 18.00
        val b = bill(hours(24, 1), penalty = "48")
        assertMoney("48.00", b.penaltyAmt)
        assertMoney("118.00", b.netAmt)
        assertMoney("100.00", b.taxableAmt)
        assertMoney("9.00", b.cgst)
        assertMoney("9.00", b.sgst)
    }

    @Test
    fun `penalty without GST is collected but not taxed`() {
        val b = bill(hours(24, 1), penalty = "48", s = settings(PENALTY_GST_FLAG to "N"))
        assertMoney("118.00", b.netAmt)
        assertMoney("59.32", b.taxableAmt)
        assertMoney("5.34", b.cgst)
        assertMoney("5.34", b.sgst)
    }

    @Test
    fun `exclusive GST with and without GST on the penalty`() {
        val withGst = bill(hours(24, 1), penalty = "30", s = settings(GST_INCLUSIVE to "N"))
        assertMoney("118.00", withGst.netAmt) // (70 + 30) × 1.18
        val withoutGst = bill(hours(24, 1), penalty = "30", s = settings(GST_INCLUSIVE to "N", PENALTY_GST_FLAG to "N"))
        assertMoney("112.60", withoutGst.netAmt) // 70 × 1.18 + 30
    }

    @Test
    fun `penalty limit comes from settings`() {
        assertMoney("1050.00", bill(hours(1), penalty = "1000", s = settings(PENALTY_MAX_AMT to "1000")).netAmt)
    }

    @Test
    fun `zero bags is rejected`() {
        assertThrows(BillCalculationException::class.java) { bill(hours(1), bags = 0) }
    }

    @Test
    fun `server example 60_50 at 18 percent splits the odd paisa to CGST`() {
        val b = bill(Duration.ofMinutes(2), s = settings(FIRST_BLOCK_RATE to "60.50"))
        assertMoney("60.50", b.netAmt)
        assertMoney("51.27", b.taxableAmt)
        assertMoney("4.62", b.cgst)
        assertMoney("4.61", b.sgst)
    }
}
