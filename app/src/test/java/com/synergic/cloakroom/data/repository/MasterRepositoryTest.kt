package com.synergic.cloakroom.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.checkin.CheckinInput
import com.synergic.cloakroom.domain.print.PrintLine
import com.synergic.cloakroom.domain.repository.CheckinResult
import com.synergic.cloakroom.domain.repository.HeaderFooterItem
import com.synergic.cloakroom.domain.repository.InvalidInputException
import com.synergic.cloakroom.domain.repository.LineType
import com.synergic.cloakroom.domain.settings.SettingKeys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
class MasterRepositoryTest {

    private lateinit var env: TestEnv
    private val logo = byteArrayOf(1, 2, 3, 4)

    @Before
    fun setUp() {
        env = TestEnv()
        env.loginOk("admin", "Admin@123")
    }

    @After
    fun tearDown() = env.close()

    private fun proofs() = runBlocking { env.masters.observeIdProofs().first().map { it.name } }
    private fun lines() = runBlocking { env.masters.observeHeaderFooter().first() }
    private fun shifts() = runBlocking { env.masters.observeShifts().first() }

    private fun line(type: LineType, text: String = "", image: ByteArray? = null, receipt: Boolean = true) =
        HeaderFooterItem(0, type, text, image, onReceipt = receipt, onBill = true, onReport = false)

    /** Admins do no counter work: a counter user checks the bag in, then the admin is back. */
    private fun checkIn(input: CheckinInput): String = runBlocking {
        env.auth.logout()
        env.loginOk("user1", "User@123")
        val t = (env.luggage.checkIn(input) as CheckinResult.Saved).transId
        env.auth.logout()
        env.loginOk("admin", "Admin@123")
        t
    }

    private fun invalid(block: suspend () -> Unit): String =
        assertThrows(InvalidInputException::class.java) { runBlocking { block() } }.message!!

    // ---- Permissions -----------------------------------------------------------------------

    @Test
    fun `normal user cannot change any master`() {
        runBlocking { env.auth.logout() }
        env.loginOk("user1", "User@123")
        val actions = listOf<suspend () -> Unit>(
            { env.masters.saveIdProof(null, "Ration Card") },
            { env.masters.deleteIdProof(1) },
            { env.masters.saveHeaderFooter(line(LineType.HEADER, "Hacked")) },
            { env.masters.deleteHeaderFooter(1) },
            { env.masters.moveHeaderFooter(1, up = false) },
            { env.masters.saveShift(null, "Extra", "01:00", "02:00") },
            { env.masters.deleteShift(1) },
        )
        actions.forEach { assertThrows(NotAuthorizedException::class.java) { runBlocking { it() } } }
        assertEquals(5, proofs().size)
        assertEquals(3, lines().size)
        assertEquals(3, shifts().size)
    }

    // ---- Cloak room details ----------------------------------------------------------------

    private fun customer() = runBlocking { env.masters.observeCustomer().first()!! }

    @Test
    fun `admin updates the editable cloak room details`() = runBlocking {
        val before = env.db.customerDao().get(Tenant.CUSTOMER_ID)!!
        env.masters.updateCustomer("  Howrah   Cloak Room ", "Platform 9, Howrah", "711 101", "19abcde1234f1z5", "howrah@sbi")
        val c = customer()
        assertEquals("Howrah Cloak Room", c.name)
        assertEquals("Platform 9, Howrah", c.address)
        assertEquals("711101", c.pinCode)
        assertEquals("19ABCDE1234F1Z5", c.gstNo)
        assertEquals("howrah@sbi", c.upiId)
        // Fields set by the super admin are untouched.
        assertEquals(before.mobileNo, c.mobile)
        assertEquals(before.email.orEmpty(), c.email)
        assertEquals(before.userLimit, c.userLimit)
        val row = env.db.customerDao().get(Tenant.CUSTOMER_ID)!!
        assertEquals("N", row.syncFlag)
        assertEquals(before.localVersion + 1, row.localVersion)
        assertEquals("admin", row.updatedBy)
    }

    @Test
    fun `new UPI ID and GSTIN are used at check-out`() = runBlocking {
        env.masters.updateCustomer("Demo Cloak Room", "Howrah", "711101", "", "new@upi")
        assertEquals("new@upi", env.bills.upiPayee()!!.vpa)
        assertNull("empty GSTIN is stored as none", env.db.customerDao().get(Tenant.CUSTOMER_ID)!!.gstNo)
        env.masters.updateCustomer("Demo Cloak Room", "Howrah", "711101", "", "")
        assertNull("no UPI ID, no payment QR", env.bills.upiPayee())
    }

    @Test
    fun `cloak room details are checked`() {
        fun update(name: String = "A", addr: String = "B", pin: String = "711101", gst: String = "", upi: String = "") =
            invalid { env.masters.updateCustomer(name, addr, pin, gst, upi) }
        assertEquals("Enter the cloak room name.", update(name = " "))
        assertEquals("Enter the address.", update(addr = " "))
        assertEquals("PIN code must be 6 digits, e.g. 711101.", update(pin = "71110"))
        assertEquals("PIN code must be 6 digits, e.g. 711101.", update(pin = "011101"))
        assertTrue(update(gst = "19ABCDE1234")!!.startsWith("Not a valid GSTIN."))
        // 15 characters but not in GSTIN format (letters where digits belong, no Z).
        assertTrue(update(gst = "1234567890RTYUY")!!.startsWith("Not a valid GSTIN."))
        assertEquals("UPI ID must look like name@bank.", update(upi = "nobank"))
        assertEquals("Demo Cloak Room", customer().name)
    }

    @Test
    fun `normal user cannot change the cloak room details`() {
        runBlocking { env.auth.logout() }
        env.loginOk("user1", "User@123")
        assertThrows(NotAuthorizedException::class.java) {
            runBlocking { env.masters.updateCustomer("X", "Y", "711101", "", "") }
        }
        assertEquals("Demo Cloak Room", customer().name)
    }

    // ---- ID proofs -------------------------------------------------------------------------

    @Test
    fun `admin adds, renames and removes ID proof types`() = runBlocking {
        env.masters.saveIdProof(null, "  Ration   Card ")
        assertTrue(proofs().contains("Ration Card"))
        assertEquals("\"ration card\" already exists.", invalid { env.masters.saveIdProof(null, "ration card") })
        assertEquals("Enter the ID proof name.", invalid { env.masters.saveIdProof(null, "  ") })

        val ration = env.masters.observeIdProofs().first().first { it.name == "Ration Card" }
        env.masters.saveIdProof(ration.id, "Ration Card (APL)")
        val renamed = env.db.idProofDao().get(ration.id)!!
        assertEquals("Ration Card (APL)", renamed.idProofName)
        assertEquals("N", renamed.syncFlag)
        assertEquals(2, renamed.localVersion)

        env.masters.deleteIdProof(ration.id)
        assertFalse(proofs().contains("Ration Card (APL)"))
        assertEquals("row is kept, only marked deleted", "Y", env.db.idProofDao().get(ration.id)!!.isDeleted)
        // The name can be used again once the old row is removed.
        env.masters.saveIdProof(null, "Ration Card (APL)")
        Unit
    }

    @Test
    fun `removed ID proof still shows on old receipts`() = runBlocking {
        val aadhaar = env.masters.observeIdProofs().first().first { it.name == "Aadhaar Card" }
        val t = checkIn(CheckinInput("1", "A", "9876543210", "1", aadhaar.id, "123456789012"))
        env.masters.deleteIdProof(aadhaar.id)
        val text = env.luggage.checkinReceipt(t)!!.lines.filterIsInstance<PrintLine.Text>().joinToString(" ") { it.text }
        assertTrue(text.contains("Aadhaar Card XXXX9012"))
    }

    // ---- Header / footer / logo ------------------------------------------------------------

    @Test
    fun `lines follow their receipt, bill and report switches`() = runBlocking {
        env.masters.saveHeaderFooter(line(LineType.FOOTER, "Bills only", receipt = false))
        val t = checkIn(CheckinInput("1", "A", "9876543210", "1", 1, "1234"))
        val receipt = env.luggage.checkinReceipt(t)!!.lines.filterIsInstance<PrintLine.Text>().joinToString(" ") { it.text }
        assertFalse(receipt.contains("Bills only"))
        val saved = lines().last()
        assertEquals(LineType.FOOTER, saved.type)
        assertFalse(saved.onReceipt)
        assertTrue(saved.onBill)
        assertFalse(saved.onReport)
    }

    @Test
    fun `text lines need text and logos need a picture`() {
        assertEquals("Enter the text of the line.", invalid { env.masters.saveHeaderFooter(line(LineType.HEADER, " ")) })
        assertEquals("Choose a logo picture.", invalid { env.masters.saveHeaderFooter(line(LineType.LOGO)) })
        assertEquals("The line is too long (max 100 characters).", invalid { env.masters.saveHeaderFooter(line(LineType.FOOTER, "x".repeat(101))) })
    }

    @Test
    fun `logo prints first on the receipt`() = runBlocking {
        env.masters.saveHeaderFooter(line(LineType.LOGO, image = logo))
        assertArrayEquals(logo, lines().single { it.type == LineType.LOGO }.image)
        val t = checkIn(CheckinInput("1", "A", "9876543210", "1", 1, "1234"))
        val first = env.luggage.checkinReceipt(t)!!.lines.first()
        assertEquals(PrintLine.Image(logo), first)
    }

    @Test
    fun `edit and remove a line`() = runBlocking {
        val footer = lines().single { it.type == LineType.FOOTER }
        env.masters.saveHeaderFooter(footer.copy(text = "Have a safe journey", onReport = true))
        val edited = lines().single { it.type == LineType.FOOTER }
        assertEquals("Have a safe journey", edited.text)
        assertTrue(edited.onReport)
        assertEquals(2, env.db.headerFooterDao().get(footer.id)!!.localVersion)

        env.masters.deleteHeaderFooter(footer.id)
        assertTrue(lines().none { it.type == LineType.FOOTER })
        assertEquals("Y", env.db.headerFooterDao().get(footer.id)!!.isDeleted)
    }

    @Test
    fun `moving swaps lines of the same type`() = runBlocking {
        val before = lines().filter { it.type == LineType.HEADER }.map { it.text }
        assertEquals(listOf("Demo Cloak Room", "Platform 1, Howrah Railway Station"), before)
        val second = lines().filter { it.type == LineType.HEADER }[1]
        env.masters.moveHeaderFooter(second.id, up = true)
        assertEquals(before.reversed(), lines().filter { it.type == LineType.HEADER }.map { it.text })
        // The first line cannot go further up, and a footer is never swapped with a header.
        val top = lines().first()
        env.masters.moveHeaderFooter(top.id, up = true)
        assertEquals(top, lines().first())
        val footer = lines().single { it.type == LineType.FOOTER }
        env.masters.moveHeaderFooter(footer.id, up = true)
        assertEquals(footer, lines().single { it.type == LineType.FOOTER })
    }

    // ---- Shifts ----------------------------------------------------------------------------

    @Test
    fun `shift times are checked and tidied`() = runBlocking {
        env.masters.saveShift(null, "Early", "4:30", "6:00")
        val early = shifts().single { it.name == "Early" }
        assertEquals("04:30", early.fromTime)
        assertEquals("06:00", early.toTime)
        assertEquals("From time must be like 06:00 (24-hour clock).", invalid { env.masters.saveShift(null, "Bad", "25:00", "06:00") })
        assertEquals("To time must be like 14:00 (24-hour clock).", invalid { env.masters.saveShift(null, "Bad", "06:00", "6pm") })
        assertEquals("Enter the shift name.", invalid { env.masters.saveShift(null, " ", "06:00", "07:00") })
    }

    @Test
    fun `edited shifts are used for the next check-in and removed ones are not`() = runBlocking {
        val morning = shifts().single { it.name == "Morning" }
        // 06:30 UTC = 12:00 IST. Shorten Morning to 06:00–11:00 and add a Noon shift.
        env.masters.saveShift(morning.id, "Morning", "06:00", "11:00")
        env.masters.saveShift(null, "Noon", "11:00", "14:00")
        val noon = shifts().single { it.name == "Noon" }
        env.clock.instant = Instant.parse("2026-11-02T06:30:00Z")
        val t1 = checkIn(CheckinInput("1", "A", "9876543210", "1", 1, "1234"))
        assertEquals(noon.id, env.db.luggageDao().get(t1)!!.shiftIdIn)

        env.masters.deleteShift(noon.id)
        val t2 = checkIn(CheckinInput("1", "A", "9876543210", "1", 1, "1234"))
        assertNull(env.db.luggageDao().get(t2)!!.shiftIdIn)
        assertEquals("Y", env.db.shiftDao().get(noon.id)!!.isDeleted)
        assertEquals(Tenant.CUSTOMER_ID, env.db.shiftDao().get(noon.id)!!.customerId)
    }
}
