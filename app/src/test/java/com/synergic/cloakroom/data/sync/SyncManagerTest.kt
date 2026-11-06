package com.synergic.cloakroom.data.sync

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.data.remote.CustomerDto
import com.synergic.cloakroom.data.remote.PullLuggageDto
import com.synergic.cloakroom.data.remote.PullResponseDto
import com.synergic.cloakroom.domain.auth.AuthException
import com.synergic.cloakroom.domain.auth.SessionState
import com.synergic.cloakroom.domain.checkin.CheckinInput
import com.synergic.cloakroom.domain.remote.ServerException
import com.synergic.cloakroom.domain.repository.CheckinResult
import com.synergic.cloakroom.domain.repository.CheckoutRequest
import com.synergic.cloakroom.domain.repository.CheckoutResult
import com.synergic.cloakroom.domain.repository.SyncOutcome
import com.synergic.cloakroom.domain.settings.PayMode
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
import java.math.BigDecimal
import java.time.Instant

/** Step f: rows saved with sync_flag N are pushed; only the server's answer sets Y. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SyncManagerTest {

    private lateinit var env: TestEnv

    @Before
    fun setUp() {
        env = TestEnv()
        env.clock.instant = Instant.parse("2026-11-02T06:30:00Z")
        env.loginOk("user1", "User@123")
    }

    @After
    fun tearDown() = env.close()

    private fun checkIn(): String = runBlocking {
        val idProof = env.luggage.observeIdProofs().first().first().id
        (env.luggage.checkIn(CheckinInput("4512345678", "Ravi Kumar", "9876543210", "1", idProof, "123456789012")) as CheckinResult.Saved).transId
    }

    private fun checkOut(transId: String) = runBlocking {
        val bag = env.bills.find(transId)!!
        val r = env.bills.checkOut(CheckoutRequest(bag, "", PayMode.CASH, true, BigDecimal("50.00")))
        check(r is CheckoutResult.Saved) { "$r" }
    }

    private fun pending(): Int = runBlocking { env.sync.pendingCount.first() }

    @Test
    fun `check-in, check-out and bill are pushed and then marked synced`() = runBlocking {
        val a = checkIn()
        val b = checkIn()
        env.clock.instant = env.clock.instant.plusSeconds(120)
        checkOut(b)
        assertEquals("2 bags + 1 bill waiting", 3, pending())

        val outcome = env.sync.syncNow()
        assertEquals(SyncOutcome.Done(pushed = 3, pending = 0), outcome)
        val push = env.syncApi.pushes.single()
        assertEquals(setOf(a, b), push.luggage.map { it.transId }.toSet())
        assertEquals("50.00", push.receipts.single().netAmt)
        with(push.receipts.single()) {
            assertEquals("options billed with (§7)", listOf("0", "Y", "Y", "Y"), listOf(graceMinutes.toString(), gstInclusive, penaltyGstFlag, gstFlag))
        }
        assertEquals("Y", env.db.luggageDao().get(a)!!.syncFlag)
        assertEquals("Y", env.db.receiptDao().get(b)!!.syncFlag)

        env.sync.syncNow()
        assertEquals("nothing new to send", 1, env.syncApi.pushes.size)
    }

    @Test
    fun `sync brings the admin's new GSTIN and UPI ID to this phone`() = runBlocking {
        val before = env.db.customerDao().get(Tenant.CUSTOMER_ID)!!
        env.syncApi.ownCustomer = CustomerDto(
            customerId = Tenant.CUSTOMER_ID, customerName = before.customerName,
            gstNo = "19AABCS1234C1Z5", upiId = "howrah.cloak@oksbi",
        )
        assertTrue(env.sync.syncNow() is SyncOutcome.Done)
        val after = env.db.customerDao().get(Tenant.CUSTOMER_ID)!!
        assertEquals("19AABCS1234C1Z5", after.gstNo)
        assertEquals("howrah.cloak@oksbi", after.upiId)
    }

    @Test
    fun `sync still succeeds when the customer cannot be fetched`() = runBlocking {
        val before = env.db.customerDao().get(Tenant.CUSTOMER_ID)
        env.syncApi.ownCustomer = null
        assertTrue(env.sync.syncNow() is SyncOutcome.Done)
        assertEquals(before, env.db.customerDao().get(Tenant.CUSTOMER_ID))
    }

    @Test
    fun `a row the server lists as an exception is also marked synced`() = runBlocking {
        val a = checkIn()
        env.syncApi.exceptions += a
        env.sync.syncNow()
        assertEquals("Y", env.db.luggageDao().get(a)!!.syncFlag)
    }

    @Test
    fun `a failed push keeps the rows waiting`() = runBlocking {
        val a = checkIn()
        env.syncApi.pushError = ServerException(ServerException.NETWORK, "timeout")
        assertTrue(env.sync.syncNow() is SyncOutcome.Failed)
        assertEquals("N", env.db.luggageDao().get(a)!!.syncFlag)
        assertTrue(env.sync.status.value.lastError != null)
    }

    @Test
    fun `logout offline is refused with the number of waiting entries`() = runBlocking {
        checkIn()
        env.connectivity.isUp = false
        val e = assertThrows(AuthException::class.java) { runBlocking { env.auth.logout() } }
        assertTrue(e.message!!, e.message!!.contains("1 entry is waiting"))
        assertTrue("still logged in", env.sessionManager.state.value is SessionState.LoggedIn)
    }

    @Test
    fun `logout online sends everything first`() = runBlocking {
        checkIn()
        env.auth.logout()
        assertEquals(1, env.syncApi.pushes.size)
        assertEquals(0, pending())
        assertTrue(env.sessionManager.state.value is SessionState.LoggedOut)
    }

    @Test
    fun `logout is refused when the push does not get through`() = runBlocking {
        checkIn()
        env.syncApi.pushError = ServerException(ServerException.NETWORK, "timeout")
        assertThrows(AuthException::class.java) { runBlocking { env.auth.logout() } }
        Unit
    }

    @Test
    fun `pull adds other counters' bags, marks check-outs and continues from server_time`() = runBlocking {
        val mine = checkIn()
        env.sync.syncNow() // first pull: no since
        assertEquals(null to null, env.syncApi.pullCalls.last())

        val other = PullLuggageDto(
            transId = "11111111-2222-3333-4444-555555555555", receiptNo = "U09-00001", receiptDt = "2026-11-02",
            userIdIn = env.userDbId("admin"), deviceIdIn = "POS-2", dateTimeIn = "2026-11-02 05:00:00",
            pasnName = "Sita Devi", pasnMobNo = "9123456780", pnrNo = "", luggageQty = 2,
        )
        val myBagOutElsewhere = PullLuggageDto(
            transId = mine, receiptNo = "U02-00001", receiptDt = "2026-11-02", userIdIn = env.userDbId("user1"),
            dateTimeIn = "2026-11-02 06:30:00", pasnName = "Ravi Kumar", pasnMobNo = "9876543210", luggageQty = 1,
            userIdOut = env.userDbId("admin"), deviceIdOut = "POS-2", dateTimeOut = "2026-11-02 06:50:00", outFlag = "Y",
        )
        env.syncApi.pullPages += PullResponseDto(luggage = listOf(other), hasMore = true, nextCursor = "c1", serverTime = "2026-11-02 07:10:00")
        env.syncApi.pullPages += PullResponseDto(luggage = listOf(myBagOutElsewhere), serverTime = "2026-11-02 07:10:00")
        env.sync.syncNow()

        assertEquals("next pull since the stored server_time, then the cursor page",
            listOf("2026-11-02 07:00:00" to null, null to "c1"), env.syncApi.pullCalls.takeLast(2))
        val added = env.db.luggageDao().get(other.transId)!!
        assertEquals("Y" to "N", added.syncFlag to added.outFlag)
        val out = env.db.luggageDao().get(mine)!!
        assertEquals("Y", out.outFlag)
        assertEquals("checked out elsewhere: nothing to send back", "Y", out.syncFlag)

        env.sync.syncNow()
        assertEquals("2026-11-02 07:10:00" to null, env.syncApi.pullCalls.last())
    }

    @Test
    fun `a new login starts the pull from scratch`() = runBlocking {
        env.sync.syncNow()
        env.sync.syncNow()
        assertEquals("2026-11-02 07:00:00", env.syncApi.pullCalls.last().first)
        env.auth.logout()
        env.loginOk("user1", "User@123")
        env.sync.syncNow()
        assertNull(env.syncApi.pullCalls.last().first)
    }
}
