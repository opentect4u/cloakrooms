package com.synergic.cloakroom.domain.checkout

import com.synergic.cloakroom.domain.settings
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.settings.SettingKeys.PENALTY_FLAG
import com.synergic.cloakroom.domain.settings.SettingKeys.UPI_ENABLED
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class CheckoutValidatorTest {

    private val good = CheckoutInput(alreadyOut = false, penalty = "", payMode = PayMode.CASH)

    private fun valid(input: CheckoutInput = good, s: AppSettings = settings()): CheckoutValidation.Valid {
        val result = CheckoutValidator.validate(input, s)
        check(result is CheckoutValidation.Valid) { "Expected valid, got $result" }
        return result
    }

    private fun errors(input: CheckoutInput, s: AppSettings = settings()): Map<CheckoutField, String> {
        val result = CheckoutValidator.validate(input, s)
        check(result is CheckoutValidation.Invalid) { "Expected invalid, got $result" }
        return result.errors
    }

    @Test
    fun `empty penalty means zero`() {
        assertEquals(0, BigDecimal.ZERO.compareTo(valid().penalty))
    }

    @Test
    fun `bag already out is refused`() {
        assertEquals("This bag is already checked out.", errors(good.copy(alreadyOut = true))[CheckoutField.BAG])
    }

    @Test
    fun `penalty must be within the limit`() {
        assertEquals(BigDecimal("500.00"), valid(good.copy(penalty = "500")).penalty)
        assertEquals("Penalty cannot be more than ₹500.00.", errors(good.copy(penalty = "501"))[CheckoutField.PENALTY])
        assertEquals("Enter the penalty as a number.", errors(good.copy(penalty = "abc"))[CheckoutField.PENALTY])
        assertEquals("Penalty cannot be negative.", errors(good.copy(penalty = "-5"))[CheckoutField.PENALTY])
        assertEquals("Penalty is not allowed.", errors(good.copy(penalty = "5"), settings(PENALTY_FLAG to "N"))[CheckoutField.PENALTY])
    }

    @Test
    fun `UPI needs no reference number`() {
        valid(good.copy(payMode = PayMode.UPI))
    }

    @Test
    fun `UPI cannot be used when switched off`() {
        assertEquals(setOf(CheckoutField.PAY_MODE), errors(good.copy(payMode = PayMode.UPI), settings(UPI_ENABLED to "N")).keys)
    }
}
