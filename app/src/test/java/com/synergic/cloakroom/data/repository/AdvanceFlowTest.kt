package com.synergic.cloakroom.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.data.db.entity.RateDtlsEntity
import com.synergic.cloakroom.domain.billing.RateSlabs
import com.synergic.cloakroom.domain.checkin.CheckinInput
import com.synergic.cloakroom.domain.print.PrintLine
import com.synergic.cloakroom.domain.qr.ReceiptQr
import com.synergic.cloakroom.domain.repository.CheckinResult
import com.synergic.cloakroom.domain.repository.CheckoutRequest
import com.synergic.cloakroom.domain.repository.CheckoutResult
import com.synergic.cloakroom.domain.repository.QrLookup
import com.synergic.cloakroom.domain.report.ReportFilter
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.settings.SettingKeys
import com.synergic.cloakroom.domain.slabs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Price ranges and the advance from check-in to bill, push and reports. Tariff of the owner's
 * example: 0–24 h ₹30 + 24–48 h ₹10, GST off, advance 24 h.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AdvanceFlowTest {

    private lateinit var env: TestEnv
    private val checkInTime = Instant.parse("2026-11-02T06:30:00Z")

    @Before
    fun setUp() {
        env = TestEnv()
        env.setSettingDirect(SettingKeys.RATE_SLABS, slabs(Triple(0, 24, "30"), Triple(24, 48, "10")))
        env.setSettingDirect(SettingKeys.GST_FLAG, "N")
        env.setSettingDirect(SettingKeys.ADVANCE_FLAG, "Y")
        env.setSettingDirect(SettingKeys.ADVANCE_HOURS, "24")
        env.loginOk("user1", "User@123")
        env.clock.instant = checkInTime
    }

    @After
    fun tearDown() = env.close()

    private fun checkIn(bags: Int): String = runBlocking {
        val idProof = env.luggage.observeIdProofs().first().first().id
        (env.luggage.checkIn(CheckinInput("4512345678", "Ravi Kumar", "9876543210", bags.toString(), idProof, "123456789012")) as CheckinResult.Saved).transId
    }

    @Test
    fun `advance is taken at check-in, printed, put in the QR and subtracted at check-out`() = runBlocking {
        assertEquals(BigDecimal("60.00"), env.luggage.advanceQuote(2))
        val transId = checkIn(2)
        val row = env.db.luggageDao().get(transId)!!
        assertEquals(60.0, row.advAmt, 0.0)
        assertEquals(24, row.advHours)

        val receipt = env.luggage.checkinReceipt(transId)!!
        val text = receipt.lines.filterIsInstance<PrintLine.Text>().map { it.text }
        assertTrue(text.first { it.startsWith("Advance paid (cash)") }.endsWith("Rs.60.00"))
        val qr = receipt.lines.filterIsInstance<PrintLine.Qr>().single().content
        assertEquals(BigDecimal("60.00"), ReceiptQr.decode(qr)!!.advAmt)
        assertTrue(qr.contains(""""a":"60.00""""))

        // Out on day 3 (50 h): 2 × (30 + 10 + 10) = 100, balance 40.
        env.clock.instant = checkInTime.plusSeconds(60 * 60 * 50)
        val bag = env.bills.find(transId)!!
        assertEquals(BigDecimal("60.00"), bag.advAmt)
        val result = env.bills.checkOut(CheckoutRequest(bag, "", PayMode.CASH, true, BigDecimal("100.00")))
        assertTrue("$result", result is CheckoutResult.Saved)
        val r = env.db.receiptDao().get(transId)!!
        assertEquals(100.0, r.netAmt, 0.0)
        assertEquals(60.0, r.advAmt, 0.0)
        assertEquals(40.0, r.balAmt, 0.0)
        assertEquals(3, r.daysCharged)
        assertEquals(slabs(Triple(0, 24, "30"), Triple(24, 48, "10")), r.rateSlabs)

        val bill = env.bills.billDocument(transId)!!.lines.filterIsInstance<PrintLine.Text>().map { it.text }
        assertTrue(bill.first { it.startsWith("Less advance paid") }.endsWith("Rs.60.00"))
        assertTrue(bill.first { it.startsWith("BALANCE PAID") }.endsWith("Rs.40.00"))
        // Owner's layout: Rent, then the advance and the balance right under it.
        val rent = bill.indexOfFirst { it.startsWith("Rent") }
        assertTrue(bill[rent].endsWith("Rs.100.00"))
        assertTrue(bill[rent + 1].startsWith("Less advance paid"))
        assertTrue(bill[rent + 2].startsWith("BALANCE PAID"))
        assertEquals(1, bill.count { it.startsWith("BALANCE PAID") })
        val lines = env.bills.billDocument(transId)!!.lines
        val balance = lines.indexOfFirst { it is PrintLine.Text && it.text.startsWith("BALANCE PAID") }
        assertEquals("a dotted line right after BALANCE PAID", PrintLine.Divider, lines[balance + 1])

        // Push: rate_slabs instead of the two-rate fields, adv_amt and bal_amt on the bill, advance on the bag.
        env.sync.syncNow()
        val push = env.syncApi.pushes.single()
        with(push.receipts.single()) {
            assertEquals(listOf(0 to 24, 24 to 48), rateSlabs!!.map { it.fromHour to it.toHour })
            assertNull(blockHours)
            assertNull(firstBlockRate)
            assertEquals("100.00" to "60.00", netAmt to advAmt)
            assertEquals("40.00", balAmt)
        }
        with(push.luggage.single()) { assertEquals("60.00" to 24, advAmt to advHours) }

        // Reports: advance on the check-in date, balance on the bill date.
        val day1 = env.reports.report(ReportFilter(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"), null))
        assertEquals(BigDecimal("60.00"), day1.advanceCollected)
        assertEquals(BigDecimal("60.00"), day1.totalCollected)
        val day3 = env.reports.report(ReportFilter(LocalDate.parse("2026-11-04"), LocalDate.parse("2026-11-04"), null))
        assertEquals(BigDecimal("100.00") to BigDecimal("40.00"), day3.total.netAmt to day3.total.balAmt)
        assertEquals(BigDecimal("40.00"), day3.totalCollected)
    }

    @Test
    fun `a bag checked out early pays nothing more and gets no refund`() = runBlocking {
        val transId = checkIn(1)
        env.clock.instant = checkInTime.plusSeconds(60 * 60)
        val bag = env.bills.find(transId)!!
        env.bills.checkOut(CheckoutRequest(bag, "", PayMode.CASH, true, BigDecimal("30.00")))
        val r = env.db.receiptDao().get(transId)!!
        assertEquals(30.0 to 0.0, r.netAmt to r.balAmt)
    }

    @Test
    fun `another counter takes the advance from the QR before sync`() = runBlocking {
        val transId = checkIn(2)
        val qr = env.luggage.checkinReceipt(transId)!!.lines.filterIsInstance<PrintLine.Qr>().single().content
        // The other counter has not seen this bag yet.
        env.db.openHelper.writableDatabase.execSQL("DELETE FROM td_luggage_in_out WHERE trans_id = ?", arrayOf(transId))
        env.clock.instant = checkInTime.plusSeconds(60 * 60 * 30)
        val bag = (env.bills.lookupQr(qr) as QrLookup.Found).bag
        assertEquals(BigDecimal("60.00"), bag.advAmt)
        env.bills.checkOut(CheckoutRequest(bag, "", PayMode.UPI, true, BigDecimal("80.00")))
        val r = env.db.receiptDao().get(transId)!!
        assertEquals(80.0 to 20.0, r.netAmt to r.balAmt)
        assertEquals(60.0, env.db.luggageDao().get(transId)!!.advAmt, 0.0)
    }

    @Test
    fun `the server's price ranges of the check-in date are used`() = runBlocking {
        env.db.rateDao().upsert(listOf(
            RateDtlsEntity(
                rateId = 1, customerId = 1, blockHours = 4, firstBlockRate = 10.0, nextBlockRate = 20.0,
                rateSlabs = slabs(Triple(0, 4, "10"), Triple(4, 24, "20")), effectiveFrom = "2026-01-01",
            ),
        ))
        env.setSettingDirect(SettingKeys.ADVANCE_FLAG, "N")
        val transId = checkIn(1)
        env.clock.instant = checkInTime.plusSeconds(60 * 60 * 25)
        assertEquals(RateSlabs.parse(slabs(Triple(0, 4, "10"), Triple(4, 24, "20"))), env.bills.tariff("2026-11-02").slabs)
        val result = env.bills.checkOut(CheckoutRequest(env.bills.find(transId)!!, "", PayMode.CASH, true, BigDecimal("50.00")))
        assertTrue("$result", result is CheckoutResult.Saved)
    }
}
