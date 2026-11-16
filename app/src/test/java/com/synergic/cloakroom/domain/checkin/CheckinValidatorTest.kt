package com.synergic.cloakroom.domain.checkin

import com.synergic.cloakroom.domain.settings
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.SettingKeys.ID_MASK_FLAG
import com.synergic.cloakroom.domain.settings.SettingKeys.ID_PROOF_REQUIRED
import com.synergic.cloakroom.domain.settings.SettingKeys.MAX_BAGS_PER_RECEIPT
import com.synergic.cloakroom.domain.settings.SettingKeys.PNR_REQUIRED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckinValidatorTest {

    private val good = CheckinInput(
        pnr = " 4512345678 ",
        name = "  Ravi   Kumar ",
        mobile = "98765 43210",
        bags = "2",
        idProofId = 1,
        idNumber = "1234 5678 9012",
    )

    private fun valid(input: CheckinInput = good, s: AppSettings = settings()): CheckinData {
        val result = CheckinValidator.validate(input, s)
        check(result is CheckinValidation.Valid) { "Expected valid, got $result" }
        return result.data
    }

    private fun errors(input: CheckinInput, s: AppSettings = settings()): Map<CheckinField, String> {
        val result = CheckinValidator.validate(input, s)
        check(result is CheckinValidation.Invalid) { "Expected invalid, got $result" }
        return result.errors
    }

    @Test
    fun `good input is cleaned and the ID is masked`() {
        val d = valid()
        assertEquals("4512345678", d.pnr)
        assertEquals("Ravi Kumar", d.name)
        assertEquals("9876543210", d.mobile)
        assertEquals(2, d.bags)
        assertEquals(1L, d.idProofId)
        assertEquals("XXXX9012", d.idNumber)
    }

    @Test
    fun `full ID number is kept when masking is off`() {
        assertEquals("123456789012", valid(s = settings(ID_MASK_FLAG to "N")).idNumber)
    }

    @Test
    fun `mobile must be a valid 10-digit number`() {
        listOf("12345", "98765432101", "5876543210", "98765abcde", "").forEach {
            assertEquals(it, setOf(CheckinField.MOBILE), errors(good.copy(mobile = it)).keys)
        }
        assertEquals("9876543210", valid(good.copy(mobile = "+91 98765 43210")).mobile)
    }

    @Test
    fun `bags must be between 1 and MAX_BAGS_PER_RECEIPT`() {
        assertEquals("Enter the number of bags (at least 1).", errors(good.copy(bags = "0"))[CheckinField.BAGS])
        assertEquals("Enter the number of bags (at least 1).", errors(good.copy(bags = ""))[CheckinField.BAGS])
        assertEquals("At most 10 bags per receipt.", errors(good.copy(bags = "11"))[CheckinField.BAGS])
        assertEquals(10, valid(good.copy(bags = "10")).bags)
        assertEquals("At most 3 bags per receipt.", errors(good.copy(bags = "4"), settings(MAX_BAGS_PER_RECEIPT to "3"))[CheckinField.BAGS])
    }

    @Test
    fun `extra bags are optional, 0 to MAX_BAGS_PER_RECEIPT`() {
        assertEquals("empty means none", 0, valid(good.copy(extraBags = "")).extraBags)
        assertEquals(0, valid(good).extraBags)
        assertEquals(3, valid(good.copy(extraBags = " 3 ")).extraBags)
        assertEquals(10, valid(good.copy(extraBags = "10")).extraBags)
        assertEquals("At most 10 extra bags per receipt.", errors(good.copy(extraBags = "11"))[CheckinField.EXTRA_BAGS])
        assertEquals(setOf(CheckinField.EXTRA_BAGS), errors(good.copy(extraBags = "abc")).keys)
        assertEquals(setOf(CheckinField.EXTRA_BAGS), errors(good.copy(extraBags = "-1")).keys)
    }

    @Test
    fun `PNR is required only when PNR_REQUIRED is on`() {
        assertEquals(setOf(CheckinField.PNR), errors(good.copy(pnr = " ")).keys)
        assertEquals("", valid(good.copy(pnr = ""), settings(PNR_REQUIRED to "N")).pnr)
        assertEquals(setOf(CheckinField.PNR), errors(good.copy(pnr = "45-12")).keys)
    }

    @Test
    fun `name is always required`() {
        assertEquals(setOf(CheckinField.NAME), errors(good.copy(name = "   ")).keys)
    }

    @Test
    fun `ID proof is required only when ID_PROOF_REQUIRED is on`() {
        assertEquals(
            setOf(CheckinField.ID_PROOF, CheckinField.ID_NUMBER),
            errors(good.copy(idProofId = null, idNumber = "")).keys,
        )
        val optional = settings(ID_PROOF_REQUIRED to "N")
        val noId = valid(good.copy(idProofId = null, idNumber = ""), optional)
        assertEquals(CheckinValidator.NO_ID_PROOF, noId.idProofId)
        assertEquals("", noId.idNumber)
        // When a type is chosen, its number is still needed, and vice versa.
        assertEquals(setOf(CheckinField.ID_NUMBER), errors(good.copy(idNumber = ""), optional).keys)
        assertEquals(setOf(CheckinField.ID_PROOF), errors(good.copy(idProofId = null), optional).keys)
    }

    @Test
    fun `masked ID needs at least 4 characters`() {
        assertEquals(setOf(CheckinField.ID_NUMBER), errors(good.copy(idNumber = "123")).keys)
    }

    @Test
    fun `all problems are reported at once`() {
        val e = errors(CheckinInput("", "", "1", "0", null, "", extraBags = "x"))
        assertTrue(e.keys.containsAll(CheckinField.entries))
    }

    @Test
    fun `mask keeps the last 4 characters`() {
        assertEquals("XXXX7890", CheckinValidator.maskId("AB1234567890"))
        assertEquals("XXXX1234", CheckinValidator.maskId("1234"))
        assertEquals("", CheckinValidator.maskId(""))
    }

    @Test
    fun `an ID type with mask flag Y is masked even when ID_MASK_FLAG is off`() {
        assertEquals("XXXX9012", valid(good.copy(idProofMasked = true), settings(ID_MASK_FLAG to "N")).idNumber)
    }
}
