package com.synergic.cloakroom.domain.billing

import com.synergic.cloakroom.domain.settings
import com.synergic.cloakroom.domain.settings.SettingKeys.GST_PCT
import com.synergic.cloakroom.domain.settings.SettingKeys.GST_TYPE
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/** §7: the server's rate and GST row replace the settings' values; an own GST % replaces the GST row. */
class TariffTest {

    private val serverSlabs = RateSlabs.fromTwoRate(12, BigDecimal("30.00"), BigDecimal("10.00"))
    private val tariff = Tariff(serverSlabs, BigDecimal("6.00"), BigDecimal("6.00"), BigDecimal("12.00"))

    @Test
    fun `rate and CGST plus SGST for intra-state`() {
        val s = settings(GST_PCT to "").withTariff(tariff) // no own GST %
        assertEquals(serverSlabs, s.rateSlabs)
        assertEquals(BigDecimal("12.00"), s.gstPct)
    }

    @Test
    fun `IGST for inter-state`() {
        assertEquals(BigDecimal("12.00"), settings(GST_TYPE to "I", GST_PCT to "").withTariff(tariff.copy(igstPct = BigDecimal("12.00"))).gstPct)
    }

    @Test
    fun `the cloak room's own GST percent replaces the GST row`() {
        assertEquals(BigDecimal("5"), settings(GST_PCT to "5").withTariff(tariff).gstPct)
    }

    @Test
    fun `missing rows keep the settings`() {
        val base = settings()
        assertEquals(base, base.withTariff(Tariff()))
        assertEquals(base, base.withTariff(null))
    }

    @Test
    fun `an invalid server price list keeps the settings`() {
        val base = settings()
        assertEquals(base.rateSlabs, base.withTariff(Tariff(listOf(RateSlab(4, 24, BigDecimal.TEN)))).rateSlabs)
    }
}
