package com.synergic.cloakroom.domain.upi

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class UpiLinkTest {

    @Test
    fun `link holds payee and exact amount`() {
        assertEquals(
            "upi://pay?pa=democloakroom%40upi&pn=Demo%20Cloak%20Room&am=70.00&cu=INR&tn=Cloak%20room%20U02-00001",
            UpiLink.build(UpiPayee("democloakroom@upi", "Demo Cloak Room"), BigDecimal("70"), "Cloak room U02-00001"),
        )
    }

    @Test
    fun `amount always has two decimals`() {
        val link = UpiLink.build(UpiPayee("a@b", "X"), BigDecimal("82.6"), "n")
        assertEquals(true, link.contains("&am=82.60&"))
    }
}
