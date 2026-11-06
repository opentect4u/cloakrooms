package com.synergic.cloakroom.domain.checkout

import com.synergic.cloakroom.domain.billing.BillCalculationException
import com.synergic.cloakroom.domain.billing.BillCalculator
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.PayMode
import java.math.BigDecimal

data class CheckoutInput(
    /** td_luggage_in_out.out_flag of the bag being billed. */
    val alreadyOut: Boolean,
    /** Text typed in the penalty field; empty means 0. */
    val penalty: String,
    val payMode: PayMode,
)

/** [AMOUNT]: the bill changed between the confirmation and the save (a new block started). */
enum class CheckoutField { BAG, PENALTY, PAY_MODE, AMOUNT }

sealed interface CheckoutValidation {
    data class Valid(val penalty: BigDecimal) : CheckoutValidation
    data class Invalid(val errors: Map<CheckoutField, String>) : CheckoutValidation
}

/** Check-out rules from CLAUDE.md; limits and switches come from [AppSettings]. */
object CheckoutValidator {

    fun validate(input: CheckoutInput, settings: AppSettings): CheckoutValidation {
        val errors = LinkedHashMap<CheckoutField, String>()

        if (input.alreadyOut) errors[CheckoutField.BAG] = "This bag is already checked out."

        var penalty = BigDecimal.ZERO
        val penaltyText = input.penalty.trim()
        if (penaltyText.isNotEmpty()) {
            val parsed = penaltyText.toBigDecimalOrNull()
            if (parsed == null) {
                errors[CheckoutField.PENALTY] = "Enter the penalty as a number."
            } else {
                try {
                    penalty = BillCalculator.checkPenalty(parsed, settings)
                } catch (e: BillCalculationException) {
                    errors[CheckoutField.PENALTY] = e.message!!
                }
            }
        }

        if (input.payMode == PayMode.UPI && !settings.upiEnabled) {
            errors[CheckoutField.PAY_MODE] = "UPI is switched off. Choose Cash."
        }

        if (errors.isNotEmpty()) return CheckoutValidation.Invalid(errors)
        return CheckoutValidation.Valid(penalty = penalty)
    }
}
