package com.synergic.cloakroom.domain.settings

import com.synergic.cloakroom.data.seed.SeedData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    private val seedValues = SeedData.settings.associate { it.name to it.value }

    @Test
    fun `seed values parse into typed settings`() {
        val s = AppSettings.fromValues(seedValues)
        assertEquals(0, "50".toBigDecimal().compareTo(s.firstBlockRate))
        assertEquals(0, "20".toBigDecimal().compareTo(s.nextBlockRate))
        assertEquals(24, s.blockHours)
        assertEquals(0, s.graceMinutes)
        assertTrue(s.gstFlag)
        assertEquals(0, "18".toBigDecimal().compareTo(s.gstPct))
        assertEquals(GstType.CGST_SGST, s.gstType)
        assertTrue(s.gstInclusive)
        assertEquals(PayMode.CASH, s.defaultPayMode)
        assertEquals(ReceiptReset.FINANCIAL_YEAR, s.receiptReset)
        assertEquals(10, s.maxBagsPerReceipt)
        assertEquals("", s.reportPwdHash)
        assertEquals(58, s.paperWidthMm)
        assertEquals(1, s.checkinPrintCopies)
    }

    @Test
    fun `every SettingKeys constant has a seed row`() {
        val keys = SettingKeys::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
        assertEquals(keys.toSet(), seedValues.keys)
    }

    @Test
    fun `blank GST percent means not set`() {
        val s = AppSettings.fromValues(seedValues + (SettingKeys.GST_PCT to ""))
        assertFalse(s.gstPctOwn)
        assertTrue(AppSettings.fromValues(seedValues).gstPctOwn)
    }

    @Test
    fun `missing setting is reported by name`() {
        val e = assertThrows(InvalidSettingException::class.java) {
            AppSettings.fromValues(seedValues - SettingKeys.GST_PCT)
        }
        assertEquals(SettingKeys.GST_PCT, e.settingName)
    }

    @Test
    fun `bad bool value is rejected`() {
        assertThrows(InvalidSettingException::class.java) {
            AppSettings.fromValues(seedValues + (SettingKeys.GST_FLAG to "yes"))
        }
    }

    @Test
    fun `unknown enum code is rejected`() {
        assertThrows(InvalidSettingException::class.java) {
            AppSettings.fromValues(seedValues + (SettingKeys.GST_TYPE to "X"))
        }
    }
}
