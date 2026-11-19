package com.synergic.cloakroom.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.seed.SeedData
import com.synergic.cloakroom.domain.slabs
import com.synergic.cloakroom.data.FakeSessionStore
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.data.remote.ApiClient
import com.synergic.cloakroom.data.remote.ApiConfig
import com.synergic.cloakroom.data.remote.AuthEvents
import com.synergic.cloakroom.data.remote.MastersApi
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.auth.UserSession
import com.synergic.cloakroom.domain.settings.SettingDataType
import com.synergic.cloakroom.domain.settings.SettingKeys
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Super admin → customer settings: §8 keys on the server, the others on this phone. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CustomerSettingsRepositoryTest {

    private lateinit var env: TestEnv
    private lateinit var server: MockWebServer
    private lateinit var repo: CustomerSettingsRepositoryImpl
    private val customer = 7L

    @Before
    fun setUp() {
        env = TestEnv()
        server = MockWebServer()
        server.start()
        val api = MastersApi(ApiClient(ApiConfig(server.url("/").toString()), FakeSessionStore().apply { saveTokens("a", "r") }, AuthEvents()))
        repo = CustomerSettingsRepositoryImpl(env.db, api, env.sessionManager, env.clock)
        env.sessionManager.set(SessionState.LoggedIn(UserSession(1, "superadmin", "Synergic", "S", "", "2026-11-02 06:30:00", false)))
    }

    @After
    fun tearDown() {
        server.shutdown()
        env.close()
    }

    private fun serverSettings(vararg rows: String) = MockResponse().setResponseCode(200)
        .setBody("""{"success":true,"data":[${rows.joinToString(",")}],"meta":{"page":1,"limit":200,"total":${rows.size}}}""")

    private fun rates(vararg rows: String) = serverSettings(*rows)
    private fun gst(vararg rows: String) = serverSettings(*rows)
    private val gstMaster = """{"gst_id":1,"cgst_pct":"9.00","sgst_pct":"9.00","igst_pct":"18.00","effective_from":"2017-07-01"}"""

    private val oldRate = """{"rate_id":1,"customer_id":7,"block_hours":24,"first_block_rate":"50.00","next_block_rate":"20.00","effective_from":"2026-01-01"}"""

    private val paperRow = """{"setting_id":4,"customer_id":7,"user_id":null,"setting_name":"PAPER_WIDTH","setting_value":"80","setting_type":"P"}"""
    private val overrideRow = """{"setting_id":9,"customer_id":7,"user_id":3,"setting_name":"QR_FLAG","setting_value":"N","setting_type":"B"}"""

    @Test
    fun `server values fill the server keys, the rest use this phone`() = runBlocking {
        server.enqueue(serverSettings(paperRow, overrideRow))
        server.enqueue(rates(oldRate))
        server.enqueue(gst(gstMaster)) // definitions(): GST
        val defs = repo.definitions(customer).associateBy { it.name }
        // An older rate row without rate_slabs: its two-rate fields are shown as price ranges.
        assertEquals("tariff comes from /rates", SeedData.DEFAULT_RATE_SLABS, defs.getValue(SettingKeys.RATE_SLABS).value)
        assertTrue(repo.isOnServer(SettingKeys.RATE_SLABS))

        assertEquals("80", defs.getValue(SettingKeys.PAPER_WIDTH).value)
        assertEquals("customer-wide value, not a user's override", "Y", defs.getValue(SettingKeys.QR_FLAG).value)
        assertEquals("Print tab key without API keeps its default", "1", defs.getValue(SettingKeys.CHECKIN_PRINT_COPIES).value)
        assertTrue(defs.getValue(SettingKeys.CHECKIN_PRINT_COPIES).description!!.contains("this phone only"))
        // §8 choices: the server accepts "Never" (N) since 2026-11-05.
        assertEquals(listOf("D", "M", "Y", "N"), defs.getValue(SettingKeys.RECEIPT_RESET).options.map { it.first })
        assertTrue("Force online after (days) is not shown", "FORCE_ONLINE_DAYS" !in defs)
    }

    @Test
    fun `saving sends server keys to the API and keeps the others on this phone`() = runBlocking {
        server.enqueue(serverSettings(paperRow)) // definitions() inside save
        server.enqueue(rates(oldRate))
        server.enqueue(gst(gstMaster)) // definitions(): GST
        server.enqueue(serverSettings(paperRow)) // rows to update
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"data":$paperRow}"""))

        assertEquals(2, repo.save(customer, mapOf(SettingKeys.PAPER_WIDTH to "58", SettingKeys.CHECKIN_PRINT_COPIES to "2")))

        repeat(4) { server.takeRequest() }
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/v1/settings/4", patch.path)
        assertEquals("""{"setting_value":"58"}""", patch.body.readUtf8())

        val local = env.db.settingDao().getScopedRows(customer, null).single { it.settingName == SettingKeys.CHECKIN_PRINT_COPIES }
        assertEquals("2", local.settingValue)
        assertEquals(customer, local.customerId)
    }

    @Test
    fun `advance hours typed by the super admin are replaced by the end of the 1st price range`() = runBlocking {
        val advanceOn = """{"setting_id":20,"customer_id":7,"user_id":null,"setting_name":"ADVANCE_FLAG","setting_value":"Y","setting_type":"B"}"""
        val hours = """{"setting_id":21,"customer_id":7,"user_id":null,"setting_name":"ADVANCE_HOURS","setting_value":"6","setting_type":"B"}"""
        server.enqueue(serverSettings(advanceOn, hours)) // definitions() inside save
        server.enqueue(rates(oldRate)) // 0–24 ₹50, 24–48 ₹20
        server.enqueue(gst(gstMaster))
        server.enqueue(serverSettings(advanceOn, hours)) // rows to update
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"data":$hours}"""))

        repo.save(customer, mapOf(SettingKeys.ADVANCE_HOURS to "10"))

        repeat(4) { server.takeRequest() }
        val patch = server.takeRequest()
        assertEquals("/api/v1/settings/21", patch.path)
        assertEquals("""{"setting_value":"24"}""", patch.body.readUtf8())
    }

    @Test
    fun `grace minutes are saved on the server`() = runBlocking {
        server.enqueue(serverSettings(paperRow)) // definitions() inside save
        server.enqueue(rates(oldRate))
        server.enqueue(gst(gstMaster)) // definitions(): GST
        server.enqueue(serverSettings(paperRow)) // rows to update: no GRACE_MINUTES row yet
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":$paperRow}"""))

        assertTrue(repo.isOnServer(SettingKeys.GRACE_MINUTES))
        repo.save(customer, mapOf(SettingKeys.GRACE_MINUTES to "15"))

        repeat(4) { server.takeRequest() }
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/v1/settings", create.path)
        val body = create.body.readUtf8()
        assertTrue(body, body.contains("\"setting_name\":\"GRACE_MINUTES\"") && body.contains("\"setting_value\":\"15\""))
    }

    @Test
    fun `penalty, GST inclusive and UPI switches are server keys, money as 2 decimals`() = runBlocking {
        listOf(
            SettingKeys.GST_INCLUSIVE, SettingKeys.PENALTY_FLAG, SettingKeys.PENALTY_MAX_AMT,
            SettingKeys.PENALTY_GST_FLAG, SettingKeys.UPI_ENABLED, SettingKeys.OUT_WITHOUT_PRINT_FLAG,
            SettingKeys.SINGLE_SESSION_FLAG, SettingKeys.PWD_MIN_LENGTH, SettingKeys.SHIFT_LOGIN_FLAG,
            SettingKeys.SHIFT_EARLY_LOGIN_MIN, SettingKeys.MAX_BAGS_PER_RECEIPT, SettingKeys.PNR_REQUIRED,
            SettingKeys.ID_PROOF_REQUIRED, SettingKeys.ID_MASK_FLAG,
        ).forEach { assertTrue(it, repo.isOnServer(it)) }

        server.enqueue(serverSettings(paperRow)) // definitions() inside save
        server.enqueue(rates(oldRate))
        server.enqueue(gst(gstMaster)) // definitions(): GST
        server.enqueue(serverSettings(paperRow)) // rows to update
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":$paperRow}"""))

        repo.save(customer, mapOf(SettingKeys.PENALTY_MAX_AMT to "750"))

        repeat(4) { server.takeRequest() }
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("\"setting_name\":\"PENALTY_MAX_AMT\"") && body.contains("\"setting_value\":\"750.00\""))
    }

    @Test
    fun `a value the server refuses is shown on its field and nothing is kept on the phone`() = runBlocking {
        server.enqueue(serverSettings(paperRow)) // definitions() inside save
        server.enqueue(rates(oldRate))
        server.enqueue(gst(gstMaster))
        server.enqueue(serverSettings(paperRow)) // rows to update
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"success":false,"message":"Validation failed","code":"VALIDATION_ERROR","data":null,
               "errors":[{"location":"body","path":"setting_value","message":"Must be 0–1440"}]}"""
        ))

        val e = assertThrows(com.synergic.cloakroom.domain.settings.SettingsValidationException::class.java) {
            runBlocking { repo.save(customer, mapOf(SettingKeys.GRACE_MINUTES to "10")) }
        }
        assertEquals("Must be 0–1440", e.errors[SettingKeys.GRACE_MINUTES])
        assertTrue("super admin: nothing on this phone", env.db.settingDao().getScopedRows(customer, null).none { it.settingName == SettingKeys.GRACE_MINUTES })
    }

    @Test
    fun `invalid values are refused before anything is sent`() {
        server.enqueue(serverSettings(paperRow))
        server.enqueue(rates(oldRate))
        server.enqueue(gst(gstMaster)) // definitions(): GST
        assertThrows(com.synergic.cloakroom.domain.settings.SettingsValidationException::class.java) {
            runBlocking { repo.save(customer, mapOf(SettingKeys.CHECKIN_PRINT_COPIES to "9")) }
        }
        assertEquals("only the definitions calls", 3, server.requestCount)
    }

    @Test
    fun `a new tariff becomes a rate from today, older rates stay`() = runBlocking {
        server.enqueue(serverSettings(paperRow)) // definitions(): settings
        server.enqueue(rates(oldRate)) // definitions(): rates
        server.enqueue(gst(gstMaster)) // definitions(): GST
        server.enqueue(serverSettings(paperRow)) // server setting rows
        server.enqueue(rates(oldRate)) // current rate
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":$oldRate}"""))

        repo.save(customer, mapOf(SettingKeys.RATE_SLABS to slabs(Triple(0, 24, "30"), Triple(24, 48, "10"))))

        repeat(5) { server.takeRequest() }
        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/api/v1/rates", create.path)
        assertEquals(
            """{"customer_id":7,"rate_slabs":[{"from_hour":0,"to_hour":24,"rate":"30.00"},{"from_hour":24,"to_hour":48,"rate":"10.00"}],"effective_from":"2026-11-02"}""",
            create.body.readUtf8(),
        )
        assertTrue("nothing kept on the phone for the tariff", env.db.settingDao().getScopedRows(customer, null).isEmpty())
    }

    @Test
    fun `changing the tariff again on the same day updates today's rate`() = runBlocking {
        val todayRate = """{"rate_id":5,"customer_id":7,"block_hours":24,"first_block_rate":"60.00","next_block_rate":"25.00","effective_from":"2026-11-02"}"""
        server.enqueue(serverSettings())
        server.enqueue(rates(todayRate, oldRate))
        server.enqueue(gst(gstMaster)) // definitions(): GST
        server.enqueue(serverSettings())
        server.enqueue(rates(todayRate, oldRate))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"data":$todayRate}"""))

        repo.save(customer, mapOf(SettingKeys.RATE_SLABS to slabs(Triple(0, 4, "10"), Triple(4, 24, "20"))))

        repeat(5) { server.takeRequest() }
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("/api/v1/rates/5", patch.path)
        val body = patch.body.readUtf8()
        assertTrue(body.contains(""""rate_slabs":[{"from_hour":0,"to_hour":4,"rate":"10.00"},{"from_hour":4,"to_hour":24,"rate":"20.00"}]"""))
        assertFalse("never the older fields with rate_slabs", body.contains("block_hours"))
    }

    @Test
    fun `an admin may use it only for their own cloak room, a counter user never`() {
        env.sessionManager.set(SessionState.LoggedIn(UserSession(2, "raj", "Raj", "A", "U01", "2026-11-02 06:30:00", false, customerId = 3)))
        assertThrows(NotAuthorizedException::class.java) { runBlocking { repo.definitions(customer) } }
        env.sessionManager.set(SessionState.LoggedIn(UserSession(4, "op", "Op", "O", "U02", "2026-11-02 06:30:00", false, customerId = 1)))
        assertThrows(NotAuthorizedException::class.java) { runBlocking { repo.definitions(1) } }
    }

    @Test
    fun `GST percent is the cloak room's own setting, shown from the shared GST table until saved`() = runBlocking {
        server.enqueue(serverSettings(paperRow))
        server.enqueue(rates(oldRate))
        server.enqueue(gst(gstMaster))
        val def = repo.definitions(customer).single { it.name == SettingKeys.GST_PCT }
        assertEquals("no own value yet: CGST 9 + SGST 9 from the shared table", "18", def.value)
        assertTrue(repo.isOnServer(SettingKeys.GST_PCT))

        server.enqueue(serverSettings(paperRow)) // definitions() inside save
        server.enqueue(rates(oldRate))
        server.enqueue(gst(gstMaster))
        server.enqueue(serverSettings(paperRow)) // server setting rows: no GST_PCT row yet
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"data":$paperRow}"""))
        repo.save(customer, mapOf(SettingKeys.GST_PCT to "5"))

        repeat(7) { server.takeRequest() }
        val create = server.takeRequest()
        assertEquals("POST" to "/api/v1/settings", create.method to create.path)
        val body = create.body.readUtf8()
        assertTrue(body, body.contains("\"setting_name\":\"GST_PCT\"") && body.contains("\"setting_value\":\"5.00\""))
    }

    @Test
    fun `single session is set by the super admin only`() = runBlocking {
        server.enqueue(serverSettings(paperRow))
        server.enqueue(rates(oldRate))
        server.enqueue(gst(gstMaster)) // definitions(): GST
        assertFalse("super admin may edit it", repo.definitions(customer).single { it.name == SettingKeys.SINGLE_SESSION_FLAG }.readOnly)

        env.sessionManager.set(SessionState.LoggedIn(UserSession(2, "admin", "Admin", "A", "U01", "2026-11-02 06:30:00", false, customerId = 1)))
        server.enqueue(serverSettings())
        server.enqueue(rates())
        server.enqueue(gst(gstMaster)) // definitions(): GST
        val defs = repo.definitions(1)
        assertTrue("admin sees it read-only", defs.single { it.name == SettingKeys.SINGLE_SESSION_FLAG }.readOnly)
        assertFalse("the admin may change their own GST %", defs.single { it.name == SettingKeys.GST_PCT }.readOnly)
        assertFalse(defs.single { it.name == SettingKeys.PNR_REQUIRED }.readOnly)

        val before = server.requestCount
        assertThrows(NotAuthorizedException::class.java) { runBlocking { repo.save(1, mapOf(SettingKeys.SINGLE_SESSION_FLAG to "N")) } }
        assertEquals("refused before any call", before, server.requestCount)
    }

    @Test
    fun `the station admin saves on the server and this phone uses the value at once`() = runBlocking {
        env.sessionManager.set(SessionState.LoggedIn(UserSession(2, "admin", "Admin", "A", "U01", "2026-11-02 06:30:00", false, customerId = 1)))
        val gstRow = """{"setting_id":12,"customer_id":1,"user_id":null,"setting_name":"GST_TYPE","setting_value":"E","setting_type":"B"}"""
        server.enqueue(serverSettings(gstRow)) // definitions: settings
        server.enqueue(rates())                 // definitions: rates
        server.enqueue(gst(gstMaster)) // definitions(): GST
        server.enqueue(serverSettings(gstRow)) // save: current rows
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"success":true,"data":${gstRow.replace("\"E\"", "\"I\"")}}"""))

        repo.save(1, mapOf(SettingKeys.GST_TYPE to "I"))

        repeat(4) { server.takeRequest() }
        val patch = server.takeRequest()
        assertEquals("PATCH" to "/api/v1/settings/12", patch.method to patch.path)
        val local = env.db.settingDao().getScopedRows(1, null).single { it.userId == null && it.settingName == SettingKeys.GST_TYPE }
        assertEquals("I" to "Y", local.settingValue to local.syncFlag)
    }
}
