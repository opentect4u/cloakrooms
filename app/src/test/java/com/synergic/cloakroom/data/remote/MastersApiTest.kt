package com.synergic.cloakroom.data.remote

import com.synergic.cloakroom.data.FakeSessionStore
import com.synergic.cloakroom.domain.billing.RateSlabs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/** API_GUIDE.md "Customer masters as super admin (S)": where customer_id goes and where it must not. */
class MastersApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MastersApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val tokens = FakeSessionStore().apply { saveTokens("a", "r") }
        api = MastersApi(ApiClient(ApiConfig(server.url("/").toString()), tokens, AuthEvents()))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun ok(data: String) = MockResponse().setResponseCode(200).setBody("""{"success":true,"data":$data,"meta":{"page":1,"limit":200,"total":1}}""")

    private val rate = """{"rate_id":2,"customer_id":7,"block_hours":24,"first_block_rate":"60.00","next_block_rate":"25.00","effective_from":"2027-04-01"}"""

    private fun body(request: okhttp3.mockwebserver.RecordedRequest) = Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun `super admin lists one customer's rows`() = runBlocking {
        server.enqueue(ok("[$rate]"))
        val rows = api.listRates(7)
        assertEquals("60.00", rows.single().firstBlockRate)
        val path = server.takeRequest().path!!
        assertTrue(path, path.startsWith("/api/v1/rates?customer_id=7&limit=200"))
    }

    @Test
    fun `station admin lists without customer_id`() = runBlocking {
        server.enqueue(ok("[]"))
        api.listShifts(null)
        assertFalse(server.takeRequest().path!!.contains("customer_id"))
    }

    @Test
    fun `create sends customer_id in the body`() = runBlocking {
        server.enqueue(ok(rate))
        api.createRate(7, RateSlabs.fromTwoRate(24, BigDecimal("60"), BigDecimal("25")), "2027-04-01")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val b = body(request)
        assertEquals("7", b["customer_id"].toString())
        // Only rate_slabs: the server refuses rate_slabs together with the older two-rate fields.
        assertEquals(
            """[{"from_hour":0,"to_hour":24,"rate":"60.00"},{"from_hour":24,"to_hour":48,"rate":"25.00"}]""",
            b["rate_slabs"].toString(),
        )
        assertNull(b["first_block_rate"])
        assertNull(b["block_hours"])
    }

    @Test
    fun `update never sends customer_id`() = runBlocking {
        server.enqueue(ok(rate))
        api.updateRate(2, RateSlabs.fromTwoRate(24, BigDecimal("60"), BigDecimal("25")), "2027-04-01")
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/v1/rates/2", request.path)
        assertNull(body(request)["customer_id"])
    }

    @Test
    fun `delete addresses the row by id`() = runBlocking {
        server.enqueue(ok("""{"shift_id":3}"""))
        api.deleteShift(3)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/shifts/3", request.path)
    }

    @Test
    fun `settings update sends only the value`() = runBlocking {
        server.enqueue(ok("""{"setting_id":5,"customer_id":7,"user_id":null,"setting_name":"SYNC_INTERVAL_MIN","setting_value":"30"}"""))
        api.updateSetting(5, "30")
        assertEquals(setOf("setting_value"), body(server.takeRequest()).keys)
    }
}
