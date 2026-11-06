package com.synergic.cloakroom.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.checkin.CheckinField
import com.synergic.cloakroom.domain.checkin.CheckinInput
import com.synergic.cloakroom.domain.print.PrintLine
import com.synergic.cloakroom.domain.qr.ReceiptQr
import com.synergic.cloakroom.domain.repository.CheckinResult
import com.synergic.cloakroom.domain.settings.SettingKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LuggageRepositoryTest {

    private lateinit var env: TestEnv

    @Before
    fun setUp() {
        env = TestEnv()
        env.loginOk("user1", "User@123")
    }

    @After
    fun tearDown() = env.close()

    private fun aadhaarId() = runBlocking { env.luggage.observeIdProofs().first().first { it.name == "Aadhaar Card" }.id }

    private fun input() = CheckinInput("4512345678", "Ravi Kumar", "9876543210", "2", aadhaarId(), "1234 5678 9012")

    private fun saved(input: CheckinInput = input()): CheckinResult.Saved = runBlocking {
        val r = env.luggage.checkIn(input)
        check(r is CheckinResult.Saved) { "Expected saved, got $r" }
        r
    }

    private fun count() = runBlocking {
        env.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM td_luggage_in_out").use { it.moveToFirst(); it.getInt(0) }
    }

    @Test
    fun `id proofs are listed by name`() = runBlocking {
        assertEquals(
            listOf("Aadhaar Card", "Driving Licence", "PAN Card", "Passport", "Voter ID"),
            env.luggage.observeIdProofs().first().map { it.name },
        )
    }

    @Test
    fun `check-in saves the row with the next receipt number`() = runBlocking {
        val first = saved()
        assertEquals("U02-00001", first.receiptNo)
        val row = env.db.luggageDao().get(first.transId)!!
        assertEquals(36, row.transId.length)
        assertEquals(Tenant.CUSTOMER_ID, row.customerId)
        assertEquals("2026-11-02", row.receiptDt)
        assertEquals("2026-11-02 06:30:00", row.dateTimeIn)
        assertEquals(env.userDbId("user1"), row.userIdIn)
        assertEquals("device-A", row.deviceIdIn)
        assertEquals("Ravi Kumar", row.pasnName)
        assertEquals("9876543210", row.pasnMobNo)
        assertEquals("4512345678", row.pnrNo)
        assertEquals(2, row.luggageQty)
        assertEquals("XXXX9012", row.idNumber)
        assertEquals("N", row.outFlag)
        assertEquals("N", row.syncFlag)
        assertEquals(1, row.localVersion)
        assertEquals("user1", row.createdBy)

        assertEquals("U02-00002", saved().receiptNo)
    }

    @Test
    fun `shift is taken from the local clock time`() = runBlocking {
        // 06:30 UTC = 12:00 IST → Morning (06:00–14:00)
        val morning = env.db.shiftDao().getActive(Tenant.CUSTOMER_ID).first { it.shiftName == "Morning" }.shiftId
        assertEquals(morning, env.db.luggageDao().get(saved().transId)!!.shiftIdIn)

        // 20:00 UTC = 01:30 IST → Night (22:00–06:00)
        env.clock.instant = Instant.parse("2026-11-02T20:00:00Z")
        val night = env.db.shiftDao().getActive(Tenant.CUSTOMER_ID).first { it.shiftName == "Night" }.shiftId
        val row = env.db.luggageDao().get(saved().transId)!!
        assertEquals(night, row.shiftIdIn)
        assertEquals("2026-11-03", row.receiptDt)
    }

    @Test
    fun `invalid input saves nothing and uses no number`() = runBlocking {
        val r = env.luggage.checkIn(input().copy(mobile = "123", bags = "0"))
        assertTrue(r is CheckinResult.Invalid)
        assertEquals(setOf(CheckinField.MOBILE, CheckinField.BAGS), (r as CheckinResult.Invalid).errors.keys)
        assertEquals(0, count())
        assertEquals("U02-00001", saved().receiptNo)
    }

    @Test
    fun `unknown ID proof type is refused`() = runBlocking {
        val r = env.luggage.checkIn(input().copy(idProofId = 999))
        assertEquals(setOf(CheckinField.ID_PROOF), (r as CheckinResult.Invalid).errors.keys)
    }

    @Test
    fun `check-in needs a login`() = runBlocking {
        env.auth.logout()
        assertThrows(NotAuthorizedException::class.java) { runBlocking { env.luggage.checkIn(input()) } }
        assertEquals(0, count())
    }

    @Test
    fun `each user has their own receipt series`() = runBlocking {
        assertEquals("U02-00001", saved().receiptNo)
        env.auth.logout()
        env.setUserType("admin", "O") // another counter user with code U01
        env.loginOk("admin", "Admin@123")
        assertEquals("U01-00001", saved().receiptNo)
    }

    @Test
    fun `receipt document holds the QR without the ID number`() = runBlocking {
        val s = saved()
        val doc = env.luggage.checkinReceipt(s.transId)!!
        assertEquals("Receipt U02-00001", doc.title)
        val qr = doc.lines.filterIsInstance<PrintLine.Qr>().single()
        val data = ReceiptQr.decode(qr.content)!!
        assertEquals(s.transId, data.transId)
        assertEquals("U02-00001", data.receiptNo)
        assertEquals(env.userDbId("user1"), data.userIdIn)
        assertFalse(qr.content.contains("9012"))
        // Long text wraps over rows, so join them with spaces before searching.
        val text = doc.lines.filterIsInstance<PrintLine.Text>().joinToString(" ") { it.text.trim() }
        assertTrue(text.contains("Platform 1, Howrah Railway Station"))
        assertTrue(text.contains("Aadhaar Card XXXX9012"))
        assertTrue(text.contains("U02 Counter User"))
        assertTrue(text.contains("Thank you!"))
    }

    @Test
    fun `unknown receipt gives null`() = runBlocking {
        assertNull(env.luggage.checkinReceipt("nope"))
    }

    @Test
    fun `admins never check in`() = runBlocking {
        env.auth.logout()
        env.loginOk("admin", "Admin@123")
        val e = assertThrows(NotAuthorizedException::class.java) { runBlocking { env.luggage.checkIn(input()) } }
        assertTrue(e.message!!.startsWith("Admins do not check in"))
        assertEquals(0, count())
    }
}
