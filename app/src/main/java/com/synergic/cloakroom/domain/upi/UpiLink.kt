package com.synergic.cloakroom.domain.upi

import com.synergic.cloakroom.domain.billing.money
import java.math.BigDecimal
import java.net.URLEncoder

/** Who receives UPI payments: md_customer.upi_id and customer_name. */
data class UpiPayee(val vpa: String, val name: String)

/**
 * Builds the standard UPI payment link shown as a QR at check-out. Any UPI app (GPay, PhonePe,
 * Paytm, BHIM…) opens it with the payee and the exact amount filled in.
 *
 * `upi://pay?pa=<vpa>&pn=<name>&am=<amount>&cu=INR&tn=<note>`
 */
object UpiLink {

    fun build(payee: UpiPayee, amount: BigDecimal, note: String): String =
        "upi://pay?pa=${enc(payee.vpa)}&pn=${enc(payee.name)}&am=${amount.money().toPlainString()}&cu=INR&tn=${enc(note)}"

    private fun enc(s: String): String = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")
}
