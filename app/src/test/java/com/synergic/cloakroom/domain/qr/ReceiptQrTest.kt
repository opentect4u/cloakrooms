package com.synergic.cloakroom.domain.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptQrTest {

    private val data = ReceiptQrData(
        transId = "5f0c3a52-8a1e-4d43-9a49-2f8e1c7c1b11",
        receiptNo = "U02-00001",
        receiptDt = "2026-11-02",
        customerId = 1,
        userIdIn = 2,
        dateTimeIn = "2026-11-02 06:30:00",
        bags = 3,
        name = "Ravi \"Raju\" Kumar \\ Jr. ঋতু",
        mobile = "9876543210",
        pnr = "4512345678",
    )

    @Test
    fun `encode and decode round trip, including quotes and Bengali text`() {
        assertEquals(data, ReceiptQr.decode(ReceiptQr.encode(data)))
    }

    @Test
    fun `json is compact with short keys`() {
        val json = ReceiptQr.encode(data.copy(name = "Ravi"))
        assertEquals(
            """{"v":1,"t":"5f0c3a52-8a1e-4d43-9a49-2f8e1c7c1b11","r":"U02-00001","d":"2026-11-02","c":1,"u":2,""" +
                """"i":"2026-11-02 06:30:00","b":3,"n":"Ravi","m":"9876543210","p":"4512345678"}""",
            json,
        )
        assertFalse(json.contains(' ') && json.contains(": "))
    }

    @Test
    fun `other QR codes are not accepted`() {
        listOf("", "hello", "upi://pay?pa=x@upi", "{}", """{"v":2,"t":"x"}""", """{"v":1,"t":"x"}""", "{\"v\":1").forEach {
            assertNull(it, ReceiptQr.decode(it))
        }
    }

    @Test
    fun `compact json handles spaces and escapes when decoding`() {
        assertEquals(
            mapOf("a" to "x\ny", "b" to -5L, "c" to "é"),
            CompactJson.decode(""" { "a" : "x\ny" , "b" : -5, "c": "é" } """),
        )
    }
}
