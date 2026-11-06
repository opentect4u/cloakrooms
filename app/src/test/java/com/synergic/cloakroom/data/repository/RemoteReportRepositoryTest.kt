package com.synergic.cloakroom.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.FakeSessionStore
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.data.remote.ApiClient
import com.synergic.cloakroom.data.remote.ApiConfig
import com.synergic.cloakroom.data.remote.AuthEvents
import com.synergic.cloakroom.data.remote.ReportsApi
import com.synergic.cloakroom.domain.report.ReportAccess
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.data.remote.SuperAdminApi
import com.synergic.cloakroom.data.remote.MastersApi
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.report.ReportFilter
import com.synergic.cloakroom.domain.settings.PayMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.LocalDate

/** The station admin's dashboard and reports from the server (§9), with the documented replies. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RemoteReportRepositoryTest {

    private lateinit var env: TestEnv
    private lateinit var server: MockWebServer
    private lateinit var repo: RemoteReportRepository
    private lateinit var masters: MastersApi
    private lateinit var superAdmin: SuperAdminRepositoryImpl

    private fun remote(customerId: Long?) = RemoteReportRepository(
        ReportsApi(ApiClient(ApiConfig(server.url("/").toString()), FakeSessionStore().apply { saveTokens("a", "r") }, AuthEvents())),
        masters, superAdmin, env.db, env.sessionManager, env.settings, env.reports, env.clock, customerId,
    )
    // Report calls run at the same time (#172): the server thread records them concurrently.
    private val requests: MutableList<RecordedRequest> = java.util.concurrent.CopyOnWriteArrayList()

    private val bag = """{"trans_id":"c360","receipt_no":"U03-00001","receipt_dt":"2026-11-02","date_time_in":"2026-11-02 05:00:00",
        "user_id_in":3,"user_name_in":"Ravi","shift_name_in":"Morning","pasn_name":"Suresh","pasn_mob_no":"9876543210","pnr_no":"45",
        "luggage_qty":2,"out_flag":"N","minutes_stored":120}"""
    private val bill = """{"trans_id":"61de","receipt_no":"U03-00002","receipt_dt":"2026-11-02","date_time_in":"2026-11-02 04:00:00",
        "user_id_in":3,"pasn_name":"Meera","pasn_mob_no":"9123456780","luggage_qty":1,"out_flag":"Y","date_time_out":"2026-11-02 06:00:00",
        "user_id_out":3,"shift_name_out":"Morning","bill_no":"U03-00001","bill_dt":"2026-11-02","days_charged":2,"net_amt":"70.00","pay_mode":"U","bill_printed":"Y"}"""

    private fun page(rows: String, total: Int, totals: String) =
        MockResponse().setBody("""{"success":true,"data":[$rows],"meta":{"page":1,"limit":200,"total":$total,"totals":$totals}}""")

    @Before
    fun setUp() {
        env = TestEnv()
        env.loginOk("admin", "Admin@123")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                val mode = request.requestUrl!!.queryParameter("pay_mode")
                return when {
                    path.endsWith("bags-in-store") -> page(bag, 1, """{"bags_entries":1,"bags":2}""")
                    path.endsWith("check-in-register") -> page(bag, 1, """{"bags_entries":1,"bags":2}""")
                    path.endsWith("check-out-register") -> page(bill, 1, """{"bags_entries":1,"bags":1,"net_amt":"70.00"}""")
                    path.endsWith("collection-summary") && mode == "C" ->
                        page("", 0, """{"bills":0,"bags":0,"base_amt":"0.00","penal_amt":"0.00","net_amt":"0.00","cash_amt":"0.00","upi_amt":"0.00"}""")
                    path.endsWith("collection-summary") ->
                        page("", 1, """{"bills":1,"bags":1,"base_amt":"70.00","penal_amt":"0.00","net_amt":"70.00","cash_amt":"0.00","upi_amt":"70.00"}""")
                    path.endsWith("gst") && mode == "C" -> page("", 0, """{"bills":0,"taxable_amt":"0.00","cgst":"0.00","sgst":"0.00","igst":"0.00","net_amt":"0.00"}""")
                    path.endsWith("gst") -> page("", 1, """{"bills":1,"taxable_amt":"59.32","cgst":"5.34","sgst":"5.34","igst":"0.00","net_amt":"70.00"}""")
                    path.endsWith("/resolve") -> MockResponse().setBody("""{"success":true,"data":{"id":2,"resolved_flag":"Y"}}""")
                    path.endsWith("reports/exceptions") -> page(
                        """{"id":2,"trans_id":"bcd8","table_name":"td_luggage_in_out","reason":"INVALID_REFERENCE: id proof 32 does not exist",
                           "payload":{"receipt_no":"US01-00002"},"user_name":"Synergic User 1","resolved_flag":"N","created_at":"2026-11-04 11:24:27"}""",
                        1, """{"exceptions":1,"unresolved":1}""",
                    )
                    path.endsWith("/users") -> page(
                        """{"id":8,"customer_id":7,"user_id":"op","user_name":"Op One","user_code":"US01","user_type":"O","allow_flag":"Y"}""", 1, "{}",
                    )
                    path.endsWith("/shifts") -> page("""{"shift_id":5,"customer_id":7,"shift_name":"Evening","from_time":"14:00:00","to_time":"22:00:00"}""", 1, "{}")
                    path.endsWith("/shift-assignments") -> page("", 0, "{}")
                    else -> MockResponse().setResponseCode(404).setBody("""{"success":false,"code":"NOT_FOUND","message":"x","data":null}""")
                }
            }
        }
        server.start()
        val client = ApiClient(ApiConfig(server.url("/").toString()), FakeSessionStore().apply { saveTokens("a", "r") }, AuthEvents())
        masters = MastersApi(client)
        superAdmin = SuperAdminRepositoryImpl(SuperAdminApi(client), masters, env.sessionManager, env.clock)
        repo = remote(null)
    }

    @After
    fun tearDown() {
        server.shutdown()
        env.close()
    }

    @Test
    fun `dashboard numbers are the server's totals for all users`() = runBlocking {
        val d = repo.observeDashboard().first()
        assertEquals(1 to 2, d.receiptsInStore to d.bagsInStore)
        assertEquals(1 to 1, d.todayCheckins to d.todayBills)
        assertEquals(BigDecimal("70.00"), d.todayCollection)
        assertTrue(d.allUsers)
    }

    @Test
    fun `report rows from the registers, collection from the server's totals`() = runBlocking {
        val r = repo.report(ReportFilter(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-01"), userId = null))
        assertEquals("dates put in order", LocalDate.parse("2026-11-01"), r.filter.from)
        assertEquals("U03-00001", r.checkins.single().receiptNo)
        assertEquals(BigDecimal("70.00"), r.bills.single().netAmt)
        assertEquals("only UPI had bills", listOf(PayMode.UPI), r.byPayMode.map { it.payMode })
        assertEquals(BigDecimal("59.32"), r.total.taxableAmt)
        assertEquals(BigDecimal("5.34"), r.total.cgst)
        assertEquals(BigDecimal("70.00"), r.total.netAmt)
        val checkinCall = requests.first { it.path!!.contains("check-in-register") }.requestUrl!!
        assertEquals("2026-11-01" to "2026-11-02", checkinCall.queryParameter("from") to checkinCall.queryParameter("to"))
    }

    @Test
    fun `bags in store of all counters`() = runBlocking {
        val bags = repo.observeBagsInStore().first()
        assertEquals("Suresh", bags.single().name)
        assertFalse(bags.single().isOut)
    }

    @Test
    fun `a counter user cannot use the server reports`() {
        runBlocking { env.auth.logout() }
        env.loginOk("user1", "User@123")
        assertThrows(NotAuthorizedException::class.java) { runBlocking { repo.reportAccess() } }
    }

    @Test
    fun `open sync exceptions are listed and can be marked resolved`() = runBlocking {
        val e = repo.exceptions().single()
        assertEquals("US01-00002", e.ref)
        assertFalse(e.isBill)
        val call = requests.last { it.path!!.contains("reports/exceptions") }.requestUrl!!
        assertEquals("N", call.queryParameter("resolved"))

        repo.resolveException(2)
        val patch = requests.last()
        assertEquals("PATCH" to "/api/v1/reports/exceptions/2/resolve", patch.method to patch.path)
    }

    @Test
    fun `the super admin sees a customer's reports with customer_id and that customer's users`() = runBlocking {
        runBlocking { env.auth.logout() }
        env.sessionManager.set(SessionState.LoggedIn(UserSession(1, "superadmin", "Synergic", "S", "", "2026-11-02 06:30:00", false)))
        val forCustomer = remote(7)
        assertTrue((forCustomer.reportAccess() as ReportAccess.Allowed).showExceptions)
        assertEquals(listOf("US01"), forCustomer.reportUsers().map { it.userCode })
        assertEquals(listOf("Evening"), forCustomer.reportShifts().map { it.name })

        val r = forCustomer.report(ReportFilter(LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-02"), userId = null))
        assertEquals(BigDecimal("70.00"), r.total.netAmt)
        val call = requests.last { it.path!!.contains("check-out-register") }.requestUrl!!
        assertEquals("7", call.queryParameter("customer_id"))
        assertEquals("the admin's own reports are refused to the super admin", true,
            runCatching { repo.reportAccess() }.exceptionOrNull() is NotAuthorizedException)
    }
}
