package com.synergic.cloakroom.data.sync

import android.app.Application
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.data.db.entity.UserCounterEntity
import com.synergic.cloakroom.data.remote.CountersDto
import com.synergic.cloakroom.data.remote.GstDto
import com.synergic.cloakroom.data.remote.HeaderFooterDto
import com.synergic.cloakroom.data.remote.IdProofDto
import com.synergic.cloakroom.data.remote.MasterUserDto
import com.synergic.cloakroom.data.remote.MastersDto
import com.synergic.cloakroom.data.remote.RateDto
import com.synergic.cloakroom.data.remote.ShiftAssignmentDto
import com.synergic.cloakroom.data.remote.ShiftDto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Login copies the server's setup into the phone (step d); the server wins over local demo rows. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ServerDataImporterTest {

    private lateinit var env: TestEnv
    private var user1Id = 0L

    @Before
    fun setUp() {
        env = TestEnv()
        user1Id = env.userDbId("user1")
    }

    @After
    fun tearDown() = env.close()

    private fun masters() = MastersDto(
        idProofs = listOf(IdProofDto(1, "Aadhaar Card", maskFlag = "Y"), IdProofDto(50, "Ration Card")),
        gst = listOf(GstDto(7, "9.00", "9.00", "18.00", "2017-07-01")),
        rates = listOf(RateDto(3, 1, 12, "40.00", "15.00", "2026-01-01")),
        shifts = listOf(ShiftDto(20, 1, "Full day", "08:00:00", "20:00:00")),
        shiftAssignments = listOf(ShiftAssignmentDto(30, 1, user1Id, 20, "2026-01-01")),
        headerFooter = listOf(
            HeaderFooterDto(40, 1, "H", lineOrder = 1, narration = "Server Cloak Room"),
            HeaderFooterDto(41, 1, "L", lineOrder = 1, image = Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP)),
        ),
        users = listOf(
            MasterUserDto(user1Id, 1, "U02", "Counter One", "O"),
            MasterUserDto(77, 1, "U05", "New Counter", "O"),
        ),
    )

    @Test
    fun loginCopiesMastersAndRemovesLocalRowsTheServerDoesNotHave() = runBlocking {
        env.authApi.masters = masters()
        env.loginOk("user1", "User@123")
        val db = env.db

        assertEquals(listOf("Aadhaar Card", "Ration Card"), db.idProofDao().getAllIncludingRemoved().filter { it.isDeleted == "N" }.map { it.idProofName })
        assertEquals("Y", db.idProofDao().get(1)!!.maskFlag)

        val gst = db.gstDao().getAll().filter { it.isDeleted == "N" }.single()
        assertEquals(18.0, gst.igstPct, 0.0)

        val rate = db.rateDao().getAll(1).single { it.isDeleted == "N" }
        assertEquals(12, rate.blockHours)
        assertEquals(40.0, rate.firstBlockRate, 0.0)
        // Billing reads these settings until step e.
        val billing = db.settingDao().getScopedRows(1, null).filter { it.userId == null }.associate { it.settingName to it.settingValue }
        assertEquals(listOf("40", "15", "12"), listOf(billing["FIRST_BLOCK_RATE"], billing["NEXT_BLOCK_RATE"], billing["BLOCK_HOURS"]))

        val shift = db.shiftDao().getActive(1).single()
        assertEquals("Full day" to "08:00", shift.shiftName to shift.fromTime)
        assertEquals(20L, db.assignShiftDao().getActive(1, user1Id).single().shiftId)

        val lines = db.headerFooterDao().getActive(1)
        assertEquals(listOf(40L, 41L), lines.map { it.id }.sorted())
        assertArrayEquals(byteArrayOf(1, 2, 3), lines.single { it.lineType == "L" }.image)

        // The logged-in user keeps their login id; a new server user waits with a placeholder.
        val users = db.userDao().getAll(1).associateBy { it.id }
        assertEquals("user1", users.getValue(user1Id).userId)
        assertEquals("#77", users.getValue(77).userId)
        assertEquals("New Counter", users.getValue(77).userName)
        assertNull("The demo admin is not on the server", db.userDao().getByUserId("admin"))
    }

    @Test
    fun serverSettingsReplaceThePhonesExceptPrintTabAndReportPassword() = runBlocking {
        env.authApi.serverSettings = mapOf("GST_TYPE" to "I", "PAPER_WIDTH" to "80", "REPORT_PWD" to "********", "UNKNOWN_KEY" to "x")
        env.loginOk("user1", "User@123")

        val rows = env.db.settingDao().getScopedRows(1, null).filter { it.userId == null }.associate { it.settingName to it.settingValue }
        assertEquals("I", rows["GST_TYPE"])
        assertEquals("58", rows["PAPER_WIDTH"])
        assertEquals("", rows["REPORT_PWD"].orEmpty())
    }

    private fun gstPct(): String? = runBlocking {
        env.db.settingDao().getScopedRows(1, null).single { it.userId == null && it.settingName == "GST_PCT" }.settingValue
    }

    private fun importGst(settings: Map<String, String>) = runBlocking {
        val m = MastersDto(gst = listOf(GstDto(8, "6.00", "6.00", "12.00", "2026-01-01")))
        ServerDataImporter(env.db).import(1, user1Id, m, settings, null, "2026-11-05 07:00:00", complete = false)
    }

    @Test
    fun withoutAnOwnGstPercentTheSettingIsBlankSoBillsUseTheSharedGstRow() {
        importGst(mapOf("GST_TYPE" to "E"))
        assertEquals("", gstPct())
        val settings = runBlocking { env.settings.getSettings(null) }
        assertEquals(false, settings.gstPctOwn)
    }

    @Test
    fun theCloakRoomsOwnGstPercentIsCopied() {
        importGst(mapOf("GST_PCT" to "5.00"))
        assertEquals("5.00", gstPct())
        assertEquals(true, runBlocking { env.settings.getSettings(null) }.gstPctOwn)
    }

    @Test
    fun countersKeepTheHigherLastNumber() = runBlocking {
        env.db.userCounterDao().insert(UserCounterEntity(customerId = 1, userId = user1Id, counterType = "B", periodKey = "FY2026-27", lastNo = 30))
        env.authApi.counters = CountersDto("Y", "FY2026-27", receiptLastNo = 12, billLastNo = 9)
        env.loginOk("user1", "User@123")

        val dao = env.db.userCounterDao()
        assertEquals(12, dao.get(1, user1Id, "R", "FY2026-27")!!.lastNo)
        assertEquals(30, dao.get(1, user1Id, "B", "FY2026-27")!!.lastNo)
    }
}
