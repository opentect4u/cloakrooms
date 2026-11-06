package com.synergic.cloakroom.data.remote

import com.synergic.cloakroom.data.FakeSessionStore
import com.synergic.cloakroom.domain.remote.ServerException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Envelope, error codes and token refresh of API_GUIDE.md §2–§3, against a local mock server. */
class ApiClientTest {

    private lateinit var server: MockWebServer
    private val tokens = FakeSessionStore()
    private val events = AuthEvents()
    private lateinit var client: ApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApiClient(ApiConfig(server.url("/").toString()), tokens, events)
        tokens.saveTokens("old-access", "old-refresh")
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(status: Int, body: String) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `success returns data and sends token and request id`() = runBlocking {
        server.enqueue(json(200, """{"success":true,"message":"OK","data":{"customer_id":1,"customer_name":"Howrah"},"requestId":"r1"}"""))
        val c = client.get("/customers/1", CustomerDto.serializer())
        assertEquals("Howrah", c.customerName)

        val request = server.takeRequest()
        assertEquals("/api/v1/customers/1", request.path)
        assertEquals("Bearer old-access", request.getHeader("Authorization"))
        assertTrue(request.getHeader("X-Request-Id")!!.length in 8..64)
    }

    @Test
    fun `list meta is read into a page`() = runBlocking {
        server.enqueue(json(200, """{"success":true,"data":[{"customer_id":1,"customer_name":"A"},{"customer_id":2,"customer_name":"B"}],"meta":{"page":1,"limit":50,"total":7}}"""))
        val page = client.getPage("/customers", CustomerDto.serializer(), mapOf("search" to "a b", "page" to 1))
        assertEquals(listOf("A", "B"), page.items.map { it.customerName })
        assertEquals(7, page.total)
        assertEquals("/api/v1/customers?search=a%20b&page=1", server.takeRequest().path)
    }

    @Test
    fun `validation errors keep the field messages`() {
        server.enqueue(
            json(400, """{"success":false,"message":"Validation failed","code":"VALIDATION_ERROR","data":null,
                "errors":[{"location":"body","path":"pin_code","message":"Must be 6 digits"}],"requestId":"r2"}""")
        )
        val e = assertThrows(ServerException::class.java) { runBlocking { client.get("/x", CustomerDto.serializer()) } }
        assertEquals(ServerException.VALIDATION_ERROR, e.code)
        assertEquals(mapOf("pin_code" to "Must be 6 digits"), e.fieldErrors)
        assertEquals("r2", e.requestId)
    }

    @Test
    fun `error objects become details`() {
        server.enqueue(
            json(409, """{"success":false,"message":"User is already logged in on another device","code":"SESSION_ACTIVE","data":null,
                "errors":{"session_started_at":"2026-11-02 13:14:30","device_id":"******H-01"}}""")
        )
        val e = assertThrows(ServerException::class.java) { runBlocking { client.post("/auth/login", null, LoginDataDto.serializer(), auth = false) } }
        assertEquals(ServerException.SESSION_ACTIVE, e.code)
        assertEquals("2026-11-02 13:14:30", e.details["session_started_at"])
    }

    @Test
    fun `non-JSON answers and unreachable servers become clear errors`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        assertEquals("HTTP_502", assertThrows(ServerException::class.java) { runBlocking { client.get("/x", CustomerDto.serializer()) } }.code)

        server.shutdown()
        assertEquals(ServerException.NETWORK, assertThrows(ServerException::class.java) { runBlocking { client.get("/x", CustomerDto.serializer()) } }.code)
    }

    @Test
    fun `TOKEN_EXPIRED refreshes once for parallel calls, stores both tokens and retries`() = runBlocking {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/api/v1/auth/refresh" -> {
                    refreshes.incrementAndGet()
                    Thread.sleep(100)
                    json(200, """{"success":true,"data":{"accessToken":"new-access","refreshToken":"new-refresh","server_time":"2026-11-02 13:14:31"}}""")
                }
                request.getHeader("Authorization") == "Bearer new-access" ->
                    json(200, """{"success":true,"data":{"customer_id":1,"customer_name":"Howrah"}}""")
                else -> json(401, """{"success":false,"message":"Token expired","code":"TOKEN_EXPIRED","data":null}""")
            }
        }
        val results = (1..3).map { async { client.get("/customers/1", CustomerDto.serializer()) } }.awaitAll()
        assertEquals(3, results.size)
        assertEquals("one refresh for everybody", 1, refreshes.get())
        assertEquals("new-access", tokens.accessToken)
        assertEquals("new-refresh", tokens.refreshToken)
    }

    @Test
    fun `SESSION_ENDED on a protected call ends the session`() = runBlocking {
        server.enqueue(json(401, """{"success":false,"message":"Logged out by admin; sync pending data and log out","code":"SESSION_ENDED","data":null}"""))
        val ended = async { withTimeout(5_000) { events.sessionEnded.first() } }
        kotlinx.coroutines.yield()
        assertThrows(ServerException::class.java) { runBlocking { client.get("/auth/me", MeDto.serializer()) } }
        assertNotNull(ended.await())
        assertTrue(ended.await().contains("ended"))
    }

    @Test
    fun `a failed refresh ends the session`() = runBlocking {
        server.enqueue(json(401, """{"success":false,"message":"Token expired","code":"TOKEN_EXPIRED","data":null}"""))
        server.enqueue(json(401, """{"success":false,"message":"Refresh token reused","code":"TOKEN_REUSED","data":null}"""))
        val ended = async { withTimeout(5_000) { events.sessionEnded.first() } }
        kotlinx.coroutines.yield()
        val e = assertThrows(ServerException::class.java) { runBlocking { client.get("/auth/me", MeDto.serializer()) } }
        assertEquals(ServerException.TOKEN_REUSED, e.code)
        assertTrue(ended.await().contains("closed for safety"))
    }
}
