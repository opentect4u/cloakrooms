package com.synergic.cloakroom.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.billing.RateSlabs
import com.synergic.cloakroom.domain.checkin.CheckinInput
import com.synergic.cloakroom.domain.checkout.CheckoutField
import com.synergic.cloakroom.domain.print.PrintLine
import com.synergic.cloakroom.domain.qr.ReceiptQr
import com.synergic.cloakroom.domain.qr.ReceiptQrData
import com.synergic.cloakroom.domain.repository.CheckinResult
import com.synergic.cloakroom.domain.repository.CheckoutBag
import com.synergic.cloakroom.domain.repository.CheckoutRequest
import com.synergic.cloakroom.domain.repository.CheckoutResult
import com.synergic.cloakroom.domain.repository.QrLookup
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.slabs
import com.synergic.cloakroom.domain.settings.SettingKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.synergic.cloakroom.data.db.entity.RateDtlsEntity
import java.math.BigDecimal
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class BillRepositoryTest {

    private lateinit var env: TestEnv
    private val checkInTime = Instant.parse("2026-11-02T06:30:00Z")

    @Before
    fun setUp() {
        env = TestEnv()
        env.loginOk("user1", "User@123")
    }

    @After
    fun tearDown() = env.close()

    private fun checkIn(name: String = "Ravi Kumar", mobile: String = "9876543210", pnr: String = "4512345678", bags: Int = 1): String =
        runBlocking {
            env.clock.instant = checkInTime
            val idProof = env.luggage.observeIdProofs().first().first().id
            val r = env.luggage.checkIn(CheckinInput(pnr, name, mobile, bags.toString(), idProof, "123456789012"))
            (r as CheckinResult.Saved).transId
        }

    private fun bag(transId: String): CheckoutBag = runBlocking { env.bills.find(transId)!! }

    private fun request(
        bag: CheckoutBag,
        expected: String,
        penalty: String = "",
        payMode: PayMode = PayMode.CASH,
        print: Boolean = true,
    ) = CheckoutRequest(bag, penalty, payMode, print, BigDecimal(expected))

    private fun checkOut(req: CheckoutRequest): CheckoutResult = runBlocking { env.bills.checkOut(req) }

    private fun setSetting(name: String, value: String) = runBlocking {
        val user = env.sessionManager.current!!
        env.auth.logout()
        env.loginOk("admin", "Admin@123")
        env.settings.updateSettings(mapOf(name to value))
        env.auth.logout()
        env.loginOk(user.userId, "User@123")
    }

    private fun billCount() = runBlocking {
        env.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM td_receipt").use { it.moveToFirst(); it.getInt(0) }
    }

    // ---- Search ----------------------------------------------------------------------------

    @Test
    fun `search by PNR, mobile, name or receipt number`() = runBlocking {
        val a = checkIn(name = "Ravi Kumar", mobile = "9876543210", pnr = "4512345678")
        val b = checkIn(name = "Sita Devi", mobile = "9123456780", pnr = "8811223344")
        assertEquals(listOf(a), env.bills.search("45123").map { it.transId })
        assertEquals(listOf(b), env.bills.search("91234").map { it.transId })
        assertEquals(listOf(a), env.bills.search("ravi").map { it.transId })
        assertEquals(listOf(b), env.bills.search("U02-00002").map { it.transId })
        assertEquals(2, env.bills.search("U02-").size)
        assertTrue(env.bills.search("  ").isEmpty())
        assertTrue("% must not match everything", env.bills.search("%").isEmpty())
    }

    @Test
    fun `bags still in store are listed first`() = runBlocking {
        val first = checkIn()
        val second = checkIn()
        env.clock.instant = checkInTime.plusSeconds(120)
        checkOut(request(bag(second), "50.00"))
        val results = env.bills.search("Ravi")
        assertEquals(listOf(first, second), results.map { it.transId })
        assertTrue(results[1].isOut)
        assertEquals("U02-00001", results[1].billNo)
    }

    // ---- Check-out -------------------------------------------------------------------------

    @Test
    fun `check-out saves the bill and marks the bag out together`() = runBlocking {
        val transId = checkIn()
        env.clock.instant = Instant.parse("2026-11-03T06:31:00Z") // 24 h 01 m later
        val result = checkOut(request(bag(transId), "70.00"))
        assertEquals(CheckoutResult.Saved(transId, "U02-00001", BigDecimal("70.00")), result)

        val inOut = env.db.luggageDao().get(transId)!!
        assertEquals("Y", inOut.outFlag)
        assertEquals("2026-11-03 06:31:00", inOut.dateTimeOut)
        assertEquals(env.userDbId("user1"), inOut.userIdOut)
        assertEquals("device-A", inOut.deviceIdOut)
        assertEquals("N", inOut.syncFlag)
        assertEquals(2, inOut.localVersion)

        val r = env.db.receiptDao().get(transId)!!
        assertEquals("U02-00001", r.billNo)
        assertEquals("2026-11-03", r.billDt)
        assertEquals(2, r.daysCharged)
        assertEquals(50.0, r.firstBlockRate, 0.0)
        assertEquals(20.0, r.nextBlockRate, 0.0)
        assertEquals(24, r.blockHours)
        assertEquals(70.0, r.baseAmt, 0.0)
        assertEquals(70.0, r.netAmt, 0.0)
        assertEquals(59.32, r.taxableAmt, 0.0)
        assertEquals(5.34, r.cgst, 0.0)
        assertEquals(5.34, r.sgst, 0.0)
        assertEquals(18.0, r.gstPct, 0.0)
        assertEquals("Y", r.gstFlag)
        assertEquals("C", r.payMode)
        assertEquals("Y", r.billPrinted)
        assertEquals("N", r.syncFlag)
    }

    @Test
    fun `the bill uses the server rate of the check-in date and the GST % setting`() = runBlocking {
        val db = env.db
        db.rateDao().upsert(listOf(
            RateDtlsEntity(rateId = 1, customerId = 1, blockHours = 24, firstBlockRate = 30.0, nextBlockRate = 10.0, effectiveFrom = "2026-01-01"),
            // From the day after check-in: not used for this bag.
            RateDtlsEntity(rateId = 2, customerId = 1, blockHours = 24, firstBlockRate = 99.0, nextBlockRate = 99.0, effectiveFrom = "2026-11-03"),
        ))
        // The cloak room's GST % (its own, or the shared table copied in at login / pull).
        env.setSettingDirect(SettingKeys.GST_PCT, "12")
        val transId = checkIn()
        env.clock.instant = Instant.parse("2026-11-03T06:31:00Z") // 24 h 01 m: 2 blocks
        assertEquals(RateSlabs.fromTwoRate(24, BigDecimal("30"), BigDecimal("10")), env.bills.tariff(bag(transId).receiptDt).slabs)

        val result = checkOut(request(bag(transId), "40.00"))
        assertEquals(CheckoutResult.Saved(transId, "U02-00001", BigDecimal("40.00")), result)
        val r = db.receiptDao().get(transId)!!
        assertEquals(30.0 to 10.0, r.firstBlockRate to r.nextBlockRate)
        assertEquals(12.0, r.gstPct, 0.0)
        assertEquals(35.71, r.taxableAmt, 0.0)
    }

    @Test
    fun `bill series is separate from the receipt series`() = runBlocking {
        val a = checkIn()
        val b = checkIn() // receipts U02-00001 and U02-00002
        env.clock.instant = checkInTime.plusSeconds(60)
        assertEquals("U02-00001", (checkOut(request(bag(a), "50.00")) as CheckoutResult.Saved).billNo)
        assertEquals("U02-00002", (checkOut(request(bag(b), "50.00")) as CheckoutResult.Saved).billNo)
    }

    @Test
    fun `out without bill print still records the bill`() = runBlocking {
        val transId = checkIn()
        checkOut(request(bag(transId), "50.00", print = false))
        assertEquals("N", env.db.receiptDao().get(transId)!!.billPrinted)
    }

    @Test
    fun `a bag cannot be checked out twice`() = runBlocking {
        val transId = checkIn()
        val before = bag(transId)
        checkOut(request(before, "50.00"))
        val again = checkOut(request(before, "50.00")) as CheckoutResult.Invalid
        assertEquals(setOf(CheckoutField.BAG), again.errors.keys)
        assertEquals(1, billCount())
        val counter = env.db.userCounterDao().get(Tenant.CUSTOMER_ID, env.userDbId("user1"), "B", "FY2026-27")!!
        assertEquals("no bill number may be used up", 1, counter.lastNo)
    }

    @Test
    fun `save is refused when the amount changed since the confirmation`() = runBlocking {
        val transId = checkIn()
        env.clock.instant = Instant.parse("2026-11-03T06:31:00Z")
        val r = checkOut(request(bag(transId), "50.00")) as CheckoutResult.Invalid
        assertTrue(r.errors.getValue(CheckoutField.AMOUNT).contains("Rs.70.00"))
        assertEquals(0, billCount())
        assertEquals("N", env.db.luggageDao().get(transId)!!.outFlag)
    }

    @Test
    fun `penalty above the limit is refused`() = runBlocking {
        val transId = checkIn()
        val r = checkOut(request(bag(transId), "551.00", penalty = "501")) as CheckoutResult.Invalid
        assertEquals(setOf(CheckoutField.PENALTY), r.errors.keys)
        assertEquals(0, billCount())
    }

    @Test
    fun `penalty is billed with GST`() = runBlocking {
        val transId = checkIn()
        env.clock.instant = Instant.parse("2026-11-03T06:31:00Z")
        checkOut(request(bag(transId), "118.00", penalty = "48"))
        val r = env.db.receiptDao().get(transId)!!
        assertEquals(48.0, r.penalAmt, 0.0)
        assertEquals(100.0, r.taxableAmt, 0.0)
        assertEquals(9.0, r.cgst, 0.0)
    }

    @Test
    fun `UPI bill is saved without a reference number`() = runBlocking {
        val transId = checkIn()
        assertTrue(checkOut(request(bag(transId), "50.00", payMode = PayMode.UPI)) is CheckoutResult.Saved)
        val saved = env.db.receiptDao().get(transId)!!
        assertEquals("U", saved.payMode)
        assertEquals(null, saved.upiRef)
    }

    @Test
    fun `a user can make any number of bills a day`() = runBlocking {
        repeat(3) {
            val transId = checkIn()
            assertTrue(checkOut(request(bag(transId), "50.00")) is CheckoutResult.Saved)
        }
    }

    @Test
    fun `without an own GST percent the shared GST row of the bill date is used`() = runBlocking {
        env.setSettingDirect(SettingKeys.GST_PCT, "") // not set by the cloak room
        env.db.gstDao().upsert(listOf(
            com.synergic.cloakroom.data.db.entity.GstEntity(gstId = 50, cgstPct = 6.0, sgstPct = 6.0, igstPct = 12.0, effectiveFrom = "2026-11-02"),
            com.synergic.cloakroom.data.db.entity.GstEntity(gstId = 51, cgstPct = 14.0, sgstPct = 14.0, igstPct = 28.0, effectiveFrom = "2026-12-01"),
        ))
        val transId = checkIn()
        checkOut(request(bag(transId), "50.00"))
        val r = env.db.receiptDao().get(transId)!!
        assertEquals("CGST 6 + SGST 6 of the bill date", 12.0, r.gstPct, 0.0)
        assertEquals("options billed with", Triple(0, "Y", "Y"), Triple(r.graceMinutes, r.gstInclusive, r.penaltyGstFlag))
    }

    @Test
    fun `old bills do not change when settings change`() = runBlocking {
        val transId = checkIn()
        env.clock.instant = Instant.parse("2026-11-03T06:31:00Z")
        checkOut(request(bag(transId), "70.00"))
        setSetting(SettingKeys.RATE_SLABS, slabs(Triple(0, 24, "100")))
        setSetting(SettingKeys.GST_PCT, "5")
        val text = env.bills.billDocument(transId)!!.lines.filterIsInstance<PrintLine.Text>().map { it.text }
        assertTrue(text.first { it.startsWith("TOTAL") }.endsWith("Rs.70.00"))
        assertTrue(text.first { it.startsWith("CGST 9%") }.endsWith("5.34"))
        assertTrue(text.first { it.startsWith("Rent") }.endsWith("Rs.70.00"))
    }

    @Test
    fun `bill document shows GSTIN, bill and receipt numbers`() = runBlocking {
        val transId = checkIn()
        checkOut(request(bag(transId), "50.00"))
        val doc = env.bills.billDocument(transId)!!
        assertEquals("Bill U02-00001", doc.title)
        val all = doc.lines.filterIsInstance<PrintLine.Text>().joinToString(" ") { it.text.trim() }
        assertTrue(all.contains("GSTIN: 19ABCDE1234F1Z5"))
        assertTrue(all.contains("U02-00001"))
        assertNull(env.bills.billDocument(checkIn()))
    }

    @Test
    fun `extra bags are saved, shown and printed but never billed`() = runBlocking {
        env.clock.instant = checkInTime
        val idProof = env.luggage.observeIdProofs().first().first().id
        val saved = env.luggage.checkIn(
            CheckinInput("4512345678", "Ravi Kumar", "9876543210", "2", idProof, "123456789012", extraBags = "3")
        ) as CheckinResult.Saved
        val transId = saved.transId
        assertEquals(3, env.db.luggageDao().get(transId)!!.extraLugageQty)

        // Receipt: printed line and the QR carry the extra bags.
        val receipt = env.luggage.checkinReceipt(transId)!!
        assertTrue(receipt.lines.filterIsInstance<PrintLine.Text>().any { it.text.startsWith("Extra bags (free)") && it.text.trim().endsWith("3") })
        val qr = receipt.lines.filterIsInstance<PrintLine.Qr>().single().content
        assertEquals(3, ReceiptQr.decode(qr)!!.extraBags)

        // Check-out shows them; the bill is for the 2 charged bags only (2 x Rs.50).
        val b = bag(transId)
        assertEquals(3, b.extraBags)
        assertTrue(checkOut(request(b, "100.00")) is CheckoutResult.Saved)
        val bill = env.db.receiptDao().get(transId)!!
        assertEquals(2, bill.luggageQty)
        assertEquals(100.0, bill.netAmt, 0.0)
        assertTrue(env.bills.billDocument(transId)!!.lines.filterIsInstance<PrintLine.Text>().any { it.text.startsWith("Extra bags (free)") })
    }

    @Test
    fun `a bag without extra bags prints no extra line`() = runBlocking {
        val transId = checkIn()
        assertEquals(0, env.db.luggageDao().get(transId)!!.extraLugageQty)
        assertTrue(env.luggage.checkinReceipt(transId)!!.lines.filterIsInstance<PrintLine.Text>().none { it.text.contains("Extra bags") })
    }

    // ---- Scanned QR ------------------------------------------------------------------------

    @Test
    fun `bill keeps the GSTIN it was printed with`() = runBlocking {
        val first = checkIn()
        checkOut(request(bag(first), "50.00"))
        assertEquals("19ABCDE1234F1Z5", env.db.receiptDao().get(first)!!.gstNo)

        // The admin changes the GSTIN afterwards.
        val user = env.sessionManager.current!!.userId
        env.auth.logout()
        env.loginOk("admin", "Admin@123")
        env.masters.updateCustomer("Demo Cloak Room", "Howrah", "711101", "19ZZZZZ9999Z1Z9", "democloakroom@upi")
        env.auth.logout()
        env.loginOk(user, "User@123")

        fun gstinLine(transId: String) = runBlocking {
            env.bills.billDocument(transId)!!.lines.filterIsInstance<PrintLine.Text>().map { it.text.trim() }.single { it.startsWith("GSTIN") }
        }
        assertEquals("GSTIN: 19ABCDE1234F1Z5", gstinLine(first))
        val second = checkIn()
        checkOut(request(bag(second), "50.00"))
        assertEquals("GSTIN: 19ZZZZZ9999Z1Z9", gstinLine(second))
    }

    @Test
    fun `bill without GST stores no GSTIN`() = runBlocking {
        setSetting(SettingKeys.GST_FLAG, "N")
        val transId = checkIn()
        checkOut(request(bag(transId), "50.00"))
        assertNull(env.db.receiptDao().get(transId)!!.gstNo)
    }

    @Test
    fun `QR of a bag on this device finds it`() = runBlocking {
        val transId = checkIn()
        val qr = env.luggage.checkinReceipt(transId)!!.lines.filterIsInstance<PrintLine.Qr>().single().content
        val found = env.bills.lookupQr(qr) as QrLookup.Found
        assertEquals(transId, found.bag.transId)
        assertNull(found.bag.fromQr)
    }

    @Test
    fun `bag checked in on another counter is billed from its QR`() = runBlocking {
        val qr = ReceiptQrData(
            transId = "11111111-2222-3333-4444-555555555555", receiptNo = "U07-00042", receiptDt = "2026-11-02",
            customerId = Tenant.CUSTOMER_ID, userIdIn = 7, dateTimeIn = "2026-11-02 06:30:00",
            bags = 2, name = "Asha", mobile = "9000000001", pnr = "", extraBags = 1,
        )
        val found = env.bills.lookupQr(ReceiptQr.encode(qr)) as QrLookup.Found
        assertEquals(qr, found.bag.fromQr)
        assertEquals(1, found.bag.extraBags)

        env.clock.instant = checkInTime.plusSeconds(600)
        val saved = checkOut(request(found.bag, "100.00")) as CheckoutResult.Saved
        assertEquals("U02-00001", saved.billNo)

        val row = env.db.luggageDao().get(qr.transId)!!
        assertEquals("U07-00042", row.receiptNo) // same receipt number: no new one is made
        assertEquals(1, row.extraLugageQty) // extra bags from the QR, still not billed (2 x Rs.50)
        assertEquals(7L, row.userIdIn) // counted as a check-in of the other user
        assertEquals("Y", row.outFlag)
        assertEquals(env.userDbId("user1"), row.userIdOut)
        assertNull(env.db.userCounterDao().get(Tenant.CUSTOMER_ID, env.userDbId("user1"), "R", "FY2026-27"))
    }

    @Test
    fun `QR of another cloak room or another app is refused`() = runBlocking {
        val other = ReceiptQrData("t", "U01-00001", "2026-11-02", 99, 1, "2026-11-02 06:30:00", 1, "A", "9000000001", "")
        assertEquals(QrLookup.OtherCustomer, env.bills.lookupQr(ReceiptQr.encode(other)))
        assertEquals(QrLookup.NotAReceipt, env.bills.lookupQr("upi://pay?pa=x@upi"))
    }

    // ---- Permissions -----------------------------------------------------------------------

    @Test
    fun `check-out needs a login and the UPI payee comes from the customer`() = runBlocking {
        assertEquals("democloakroom@upi", env.bills.upiPayee()!!.vpa)
        val transId = checkIn()
        val b = bag(transId)
        env.auth.logout()
        assertThrows(NotAuthorizedException::class.java) { runBlocking { env.bills.checkOut(request(b, "50.00")) } }
        assertThrows(NotAuthorizedException::class.java) { runBlocking { env.bills.search("Ravi") } }
        Unit
    }
}
