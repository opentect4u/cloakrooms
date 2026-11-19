package com.synergic.cloakroom.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.domain.checkin.CheckinInput
import com.synergic.cloakroom.domain.repository.CheckinResult
import com.synergic.cloakroom.domain.repository.CheckoutRequest
import com.synergic.cloakroom.domain.settings.PayMode
import com.synergic.cloakroom.domain.subscription.SubscriptionExpiredException
import com.synergic.cloakroom.domain.subscription.SubscriptionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** A logged-in user of a customer whose renewal date has passed cannot check in or check out. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SubscriptionExpiryTest {

    private lateinit var env: TestEnv

    @Before
    fun setUp() {
        env = TestEnv()
        env.loginOk("user1", "User@123")
        // 2026-11-18 18:00 IST.
        env.clock.instant = Instant.parse("2026-11-18T12:30:00Z")
    }

    @After
    fun tearDown() = env.close()

    private fun setRenewal(date: String?) = runBlocking {
        val c = env.db.customerDao().get(1)!!
        env.db.customerDao().upsert(c.copy(renewalDt = date))
    }

    private fun checkIn() = runBlocking {
        val idProof = env.luggage.observeIdProofs().first().first().id
        env.luggage.checkIn(CheckinInput("4512345678", "Ravi Kumar", "9876543210", "1", idProof, "123456789012"))
    }

    @Test
    fun `the end date itself still works and shows the notice`() = runBlocking {
        setRenewal("2026-11-18")
        assertTrue(checkIn() is CheckinResult.Saved)
        assertEquals(SubscriptionStatus.ExpiringSoon(LocalDate.parse("2026-11-18"), 0), env.subscription.observeStatus().first())
    }

    @Test
    fun `the day after the end date check-in and check-out are refused but the user stays logged in`() = runBlocking {
        setRenewal("2026-11-18")
        val transId = (checkIn() as CheckinResult.Saved).transId

        // Just after midnight IST on 19-11-2026.
        env.clock.instant = Instant.parse("2026-11-18T18:31:00Z")
        val inError = runCatching { checkIn() }.exceptionOrNull()
        assertTrue("$inError", inError is SubscriptionExpiredException)
        assertTrue(inError!!.message!!.contains("18-11-2026"))

        val bag = env.bills.find(transId)
        assertNotNull("Searching a bag still works", bag)
        val outError = runCatching { env.bills.checkOut(CheckoutRequest(bag!!, "", PayMode.CASH, true, BigDecimal("50.00"))) }.exceptionOrNull()
        assertTrue("$outError", outError is SubscriptionExpiredException)
        assertNull("Bag is still in store", env.db.luggageDao().get(transId)!!.dateTimeOut)

        assertNotNull("Still logged in", env.sessionManager.current)
        assertEquals(SubscriptionStatus.Expired(LocalDate.parse("2026-11-18")), env.subscription.observeStatus().first())
    }

    @Test
    fun `a renewal brought by sync unblocks at once`() = runBlocking {
        setRenewal("2026-11-10")
        assertTrue(runCatching { checkIn() }.exceptionOrNull() is SubscriptionExpiredException)
        setRenewal("2027-11-18")
        assertTrue(checkIn() is CheckinResult.Saved)
        assertEquals(SubscriptionStatus.Active, env.subscription.observeStatus().first())
    }

    @Test
    fun `no renewal date blocks nothing`() {
        setRenewal(null)
        assertTrue(checkIn() is CheckinResult.Saved)
    }
}
