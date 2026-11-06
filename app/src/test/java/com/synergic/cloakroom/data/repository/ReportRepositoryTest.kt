package com.synergic.cloakroom.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.checkin.CheckinInput
import com.synergic.cloakroom.domain.print.PrintLine
import com.synergic.cloakroom.domain.report.DashboardData
import com.synergic.cloakroom.domain.report.ReportAccess
import com.synergic.cloakroom.domain.report.ReportFilter
import com.synergic.cloakroom.domain.repository.CheckinResult
import com.synergic.cloakroom.domain.repository.CheckoutRequest
import com.synergic.cloakroom.domain.repository.CheckoutResult
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.settings.SettingKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ReportRepositoryTest {

    private lateinit var env: TestEnv
    private val nov2 = LocalDate.of(2026, 11, 2)
    private val passwords = mapOf("admin" to "Admin@123", "user1" to "User@123")

    @Before
    fun setUp() {
        env = TestEnv()
    }

    @After
    fun tearDown() = env.close()

    private fun loginAs(userId: String) {
        if (env.sessionManager.current != null) runBlocking { env.auth.logout() }
        env.loginOk(userId, passwords.getValue(userId))
    }

    private fun checkIn(bags: Int = 1): String = runBlocking {
        env.clock.instant = Instant.parse("2026-11-02T06:30:00Z")
        val idProof = env.luggage.observeIdProofs().first().first().id
        (env.luggage.checkIn(CheckinInput("4512345678", "Ravi", "9876543210", bags.toString(), idProof, "1234")) as CheckinResult.Saved).transId
    }

    /** Checks out 10 minutes after check-in: ₹50 per bag inclusive of 18% GST, plus [penalty]. */
    private fun checkOut(transId: String, pay: PayMode = PayMode.CASH, penalty: String = "") = runBlocking {
        env.clock.instant = Instant.parse("2026-11-02T06:40:00Z")
        val bag = env.bills.find(transId)!!
        val net = BigDecimal(50 * bag.bags) + (penalty.toBigDecimalOrNull() ?: BigDecimal.ZERO)
        val r = env.bills.checkOut(CheckoutRequest(bag, penalty, pay, true, net.setScale(2)))
        check(r is CheckoutResult.Saved) { "Check-out failed: $r" }
    }

    private fun setSetting(name: String, value: String) = runBlocking {
        val back = env.sessionManager.current?.userId
        loginAs("admin")
        env.settings.updateSettings(mapOf(name to value))
        runBlocking { env.auth.logout() }
        back?.let { loginAs(it) }
    }

    private fun report(userId: Long? = null) = runBlocking { env.reports.report(ReportFilter(nov2, nov2, userId)) }

    /**
     * user1 checks in 2 receipts (3 bags) and bills one by cash; admin (old rows from when they
     * were a counter user) checks in 1 and bills it by UPI with a penalty.
     */
    private fun seedActivity() {
        loginAs("user1")
        val a = checkIn(bags = 2)
        checkIn(bags = 1)
        checkOut(a)
        env.setUserType("admin", "O")
        loginAs("admin")
        val c = checkIn()
        checkOut(c, pay = PayMode.UPI, penalty = "18")
        runBlocking { env.auth.logout() }
        env.setUserType("admin", "A")
    }

    @Test
    fun `normal user sees only their own check-ins and bills`() {
        seedActivity()
        loginAs("user1")
        val r = report()
        assertEquals(env.userDbId("user1"), r.filter.userId)
        assertEquals(2, r.checkins.size)
        assertEquals(3, r.bagsCheckedIn)
        assertEquals(1, r.bills.size)
        assertEquals("U02-00001", r.bills.single().billNo)
        assertEquals(BigDecimal("100.00"), r.total.netAmt)
    }

    @Test
    fun `normal user cannot ask for another user or all users explicitly`() {
        seedActivity()
        loginAs("user1")
        assertThrows(NotAuthorizedException::class.java) { report(userId = env.userDbId("admin")) }
        assertThrows(NotAuthorizedException::class.java) { runBlocking { env.reports.reportUsers() } }
    }

    @Test
    fun `admin sees all users and can filter by user`() {
        seedActivity()
        loginAs("admin")
        val all = report()
        assertNull(all.filter.userId)
        assertEquals(3, all.checkins.size)
        assertEquals(2, all.bills.size)
        assertEquals(1, report(userId = env.userDbId("admin")).bills.size)
        assertEquals("admins are never in the user filter", listOf("U02"), runBlocking { env.reports.reportUsers() }.map { it.userCode })
    }

    @Test
    fun `collection is split by pay mode with the GST breakup`() {
        seedActivity()
        loginAs("admin")
        val r = report()
        val cash = r.byPayMode.single { it.payMode == PayMode.CASH }
        val upi = r.byPayMode.single { it.payMode == PayMode.UPI }
        // ₹100 inclusive of 18%: taxable 84.75, GST 15.25 → 7.63 + 7.62
        assertEquals(1, cash.bills)
        assertEquals(BigDecimal("100.00"), cash.netAmt)
        assertEquals(BigDecimal("84.75"), cash.taxableAmt)
        assertEquals(BigDecimal("7.63"), cash.cgst)
        assertEquals(BigDecimal("7.62"), cash.sgst)
        // ₹50 + ₹18 penalty = ₹68 inclusive: taxable 57.63, GST 10.37 → 5.19 + 5.18
        assertEquals(BigDecimal("68.00"), upi.netAmt)
        assertEquals(BigDecimal("18.00"), upi.penaltyAmt)
        assertEquals(BigDecimal("57.63"), upi.taxableAmt)
        assertEquals(BigDecimal("168.00"), r.total.netAmt)
        assertEquals(cash.cgst + upi.cgst, r.total.cgst)
        assertEquals(2, r.total.bills)
    }

    @Test
    fun `date range filters by local date`() {
        seedActivity()
        loginAs("admin")
        val next = runBlocking { env.reports.report(ReportFilter(nov2.plusDays(1), nov2.plusDays(5), null)) }
        assertTrue(next.checkins.isEmpty())
        assertTrue(next.bills.isEmpty())
        // From and To in the wrong order are swapped.
        val swapped = runBlocking { env.reports.report(ReportFilter(nov2, nov2.minusDays(3), null)) }
        assertEquals(nov2.minusDays(3), swapped.filter.from)
        assertEquals(3, swapped.checkins.size)
    }

    @Test
    fun `REPORT_FLAG off blocks normal users but not admins`() {
        setSetting(SettingKeys.REPORT_FLAG, "N")
        loginAs("user1")
        assertEquals(ReportAccess.Denied, runBlocking { env.reports.reportAccess() })
        assertThrows(NotAuthorizedException::class.java) { report() }
        loginAs("admin")
        assertEquals(ReportAccess.Allowed(needsPassword = false, canChooseUser = true), runBlocking { env.reports.reportAccess() })
        report()
    }

    @Test
    fun `report password must be entered first`() = runBlocking {
        loginAs("admin")
        env.settings.updateSettings(mapOf(SettingKeys.REPORT_PWD_FLAG to "Y", SettingKeys.REPORT_PWD to "Rep@2026"))
        assertEquals(ReportAccess.Allowed(needsPassword = true, canChooseUser = true), env.reports.reportAccess())
        assertThrows(NotAuthorizedException::class.java) { report() }
        assertFalse(env.reports.verifyReportPassword("wrong"))
        assertThrows(NotAuthorizedException::class.java) { report() }
        assertTrue(env.reports.verifyReportPassword("Rep@2026"))
        report()
        // A new login (later) must enter it again.
        env.clock.instant = Instant.parse("2026-11-02T07:00:00Z")
        loginAs("admin")
        assertThrows(NotAuthorizedException::class.java) { report() }
        Unit
    }

    @Test
    fun `reports need a login`() {
        assertThrows(NotAuthorizedException::class.java) { report() }
    }

    @Test
    fun `report document has the totals`() = runBlocking {
        seedActivity()
        loginAs("admin")
        val doc = env.reports.reportDocument(ReportFilter(nov2, nov2, null))
        val rows = doc.lines.filterIsInstance<PrintLine.Text>().map { it.text }
        assertTrue(rows.all { it.length <= 32 })
        val all = rows.joinToString(" ") { it.trim() }
        assertTrue(all.contains("COLLECTION REPORT"))
        assertTrue(all.contains("02-11-2026"))
        assertTrue(all.contains("All users"))
        assertTrue(rows.first { it.startsWith("Collected") }.endsWith("Rs.168.00"))
        assertTrue(rows.any { it.startsWith("U02-00001 Cash") && it.endsWith("100.00") })
    }

    // ---- Shift filter ----------------------------------------------------------------------

    private fun shiftId(name: String) = runBlocking { env.reports.reportShifts().first { it.name == name }.id }

    /**
     * Bag A: checked in 12:00 IST (Morning), billed 16:00 IST (Evening).
     * Bag B: checked in 15:30 IST (Evening), still in store.
     */
    private fun seedShifts(): Pair<String, String> = runBlocking {
        loginAs("user1")
        val idProof = env.luggage.observeIdProofs().first().first().id
        fun input() = CheckinInput("4512345678", "Ravi", "9876543210", "1", idProof, "1234")
        env.clock.instant = Instant.parse("2026-11-02T06:30:00Z")
        val a = (env.luggage.checkIn(input()) as CheckinResult.Saved).transId
        env.clock.instant = Instant.parse("2026-11-02T10:00:00Z")
        val b = (env.luggage.checkIn(input()) as CheckinResult.Saved).transId
        env.clock.instant = Instant.parse("2026-11-02T10:30:00Z")
        val bag = env.bills.find(a)!!
        check(env.bills.checkOut(CheckoutRequest(bag, "", PayMode.CASH, true, BigDecimal("50.00"))) is CheckoutResult.Saved)
        a to b
    }

    @Test
    fun `shift filter uses the check-in shift for check-ins and the billing shift for bills`() {
        val (a, b) = seedShifts()
        val morning = runBlocking { env.reports.report(ReportFilter(nov2, nov2, null, shiftId("Morning"))) }
        assertEquals(listOf(a), morning.checkins.map { it.transId })
        assertTrue("bill was made in the Evening shift", morning.bills.isEmpty())
        assertEquals("Morning", morning.checkins.single().shift)

        val evening = runBlocking { env.reports.report(ReportFilter(nov2, nov2, null, shiftId("Evening"))) }
        assertEquals(listOf(b), evening.checkins.map { it.transId })
        assertEquals(listOf(a), evening.bills.map { it.transId })
        assertEquals("Evening", evening.bills.single().shift)
        assertEquals(BigDecimal("50.00"), evening.total.netAmt)

        val all = report()
        assertEquals(2, all.checkins.size)
        assertEquals(1, all.bills.size)
        assertEquals(shiftId("Evening"), runBlocking { env.reports.report(ReportFilter(nov2, nov2, null, shiftId("Evening"))) }.filter.shiftId)
    }

    @Test
    fun `admin can combine the user and shift filters`() {
        seedShifts()
        loginAs("admin")
        val user1Evening = runBlocking {
            env.reports.report(ReportFilter(nov2, nov2, env.userDbId("user1"), shiftId("Evening")))
        }
        assertEquals(1, user1Evening.checkins.size)
        val adminEvening = runBlocking {
            env.reports.report(ReportFilter(nov2, nov2, env.userDbId("admin"), shiftId("Evening")))
        }
        assertTrue(adminEvening.checkins.isEmpty())
    }

    @Test
    fun `removed shifts stay in the filter list, marked removed`() = runBlocking {
        loginAs("admin")
        val night = shiftId("Night")
        env.masters.deleteShift(night)
        val shifts = env.reports.reportShifts()
        assertEquals(listOf("Morning", "Evening", "Night"), shifts.map { it.name })
        assertTrue(shifts.single { it.id == night }.removed)
        assertEquals("Night (22:00–06:00) · removed", shifts.single { it.id == night }.label)
    }

    @Test
    fun `report document names the shift`() = runBlocking {
        seedShifts()
        val doc = env.reports.reportDocument(ReportFilter(nov2, nov2, null, shiftId("Morning")))
        val all = doc.lines.filterIsInstance<PrintLine.Text>().joinToString(" ") { it.text.trim() }
        assertTrue(all, all.contains("Morning (06:00–14:00)"))
        val allShifts = env.reports.reportDocument(ReportFilter(nov2, nov2, null))
        assertTrue(allShifts.lines.filterIsInstance<PrintLine.Text>().any { it.text.trim() == "All shifts" })
    }

    // ---- Dashboard -------------------------------------------------------------------------

    private fun awaitDashboard(predicate: (DashboardData) -> Boolean): DashboardData = runBlocking {
        withTimeout(5_000) { env.reports.observeDashboard().first(predicate) }
    }

    @Test
    fun `dashboard shows bags in store and the user's own day`() {
        seedActivity() // in store: user1's 1-bag receipt
        loginAs("user1")
        val d = awaitDashboard { true }
        assertEquals(1, d.receiptsInStore)
        assertEquals(1, d.bagsInStore)
        assertEquals(2, d.todayCheckins)
        assertEquals(1, d.todayBills)
        assertEquals(BigDecimal("100.00"), d.todayCollection)
        assertFalse(d.allUsers)
    }

    @Test
    fun `admin dashboard counts all users and hides collection when switched off`() {
        seedActivity()
        loginAs("admin")
        val d = awaitDashboard { true }
        assertTrue(d.allUsers)
        assertEquals(3, d.todayCheckins)
        assertEquals(BigDecimal("168.00"), d.todayCollection)
        setSetting(SettingKeys.TOTAL_COLLECTION_FLAG, "N")
        assertNull(awaitDashboard { it.todayCollection == null }.todayCollection)
    }

    @Test
    fun `bags in store list is live`() = runBlocking {
        loginAs("user1")
        val t = checkIn(bags = 2)
        // Generous waits: on a busy build machine Room's first query can take a few seconds.
        assertEquals(listOf(t), withTimeout(20_000) { env.luggage.observeBagsInStore().first { it.isNotEmpty() } }.map { it.transId })
        checkOut(t)
        assertTrue(withTimeout(20_000) { env.luggage.observeBagsInStore().first { it.isEmpty() } }.isEmpty())
    }

    @Test
    fun `admins are left out of the user filter while they do no counter work`() {
        loginAs("admin")
        assertEquals(listOf("U02"), runBlocking { env.reports.reportUsers() }.map { it.userCode })
    }
}
