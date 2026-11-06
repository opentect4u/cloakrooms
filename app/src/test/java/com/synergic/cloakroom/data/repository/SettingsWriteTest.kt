package com.synergic.cloakroom.data.repository

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.TestEnv
import com.synergic.cloakroom.data.db.entity.SettingEntity
import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.settings.AppSettings
import com.synergic.cloakroom.domain.settings.PrinterType
import com.synergic.cloakroom.domain.settings.SettingKeys
import com.synergic.cloakroom.domain.settings.SettingsValidationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SettingsWriteTest {

    private lateinit var env: TestEnv

    @Before
    fun setUp() {
        env = TestEnv()
    }

    @After
    fun tearDown() = env.close()

    private fun row(name: String) = runBlocking {
        env.db.settingDao().getScopedRows(Tenant.CUSTOMER_ID, null).first { it.settingName == name }
    }

    private fun logs() = runBlocking { env.db.settingDao().getLogs(Tenant.CUSTOMER_ID) }

    private fun errorsOf(changes: Map<String, String>): Map<String, String> =
        assertThrows(SettingsValidationException::class.java) {
            runBlocking { env.settings.updateSettings(changes) }
        }.errors

    // ---- Permissions -----------------------------------------------------------------------

    @Test
    fun `normal user cannot write settings`() {
        env.loginOk("user1", "User@123")
        assertThrows(NotAuthorizedException::class.java) {
            runBlocking { env.settings.updateSettings(mapOf(SettingKeys.FIRST_BLOCK_RATE to "1")) }
        }
        assertEquals("50", row(SettingKeys.FIRST_BLOCK_RATE).settingValue)
        assertTrue("no audit row may be written", logs().isEmpty())
    }

    @Test
    fun `settings cannot be written without a login`() {
        assertThrows(NotAuthorizedException::class.java) {
            runBlocking { env.settings.updateSettings(mapOf(SettingKeys.GST_FLAG to "N")) }
        }
        assertEquals("Y", row(SettingKeys.GST_FLAG).settingValue)
    }

    @Test
    fun `admin writes a setting with sync stamp and audit row`() = runBlocking {
        env.loginOk("admin", "Admin@123")
        val before = row(SettingKeys.FIRST_BLOCK_RATE)

        assertEquals(1, env.settings.updateSettings(mapOf(SettingKeys.FIRST_BLOCK_RATE to " 60 ")))

        val after = row(SettingKeys.FIRST_BLOCK_RATE)
        assertEquals("60", after.settingValue)
        assertEquals("N", after.syncFlag)
        assertEquals(before.localVersion + 1, after.localVersion)
        assertEquals("admin", after.updatedBy)

        val log = logs().single()
        assertEquals(SettingKeys.FIRST_BLOCK_RATE, log.settingName)
        assertEquals("50", log.oldValue)
        assertEquals("60", log.newValue)
        assertEquals("admin", log.changedBy)
        assertEquals("2026-11-02 06:30:00", log.changedAt)

        assertEquals(0, "60".toBigDecimal().compareTo(env.settings.getSettings(null).firstBlockRate))
    }

    @Test
    fun `unchanged values are not saved or logged`() = runBlocking {
        env.loginOk("admin", "Admin@123")
        assertEquals(0, env.settings.updateSettings(mapOf(SettingKeys.FIRST_BLOCK_RATE to "50")))
        assertTrue(logs().isEmpty())
    }

    // ---- Validation ------------------------------------------------------------------------

    @Test
    fun `invalid values are refused and nothing is saved`() {
        env.loginOk("admin", "Admin@123")
        val errors = errorsOf(
            mapOf(
                SettingKeys.GST_PCT to "30",
                SettingKeys.BLOCK_HOURS to "0",
                SettingKeys.PRINT_COPIES to "1.5",
                SettingKeys.GST_TYPE to "X",
                SettingKeys.QR_FLAG to "yes",
                SettingKeys.FIRST_BLOCK_RATE to "70",
            )
        )
        assertEquals(
            setOf(SettingKeys.GST_PCT, SettingKeys.BLOCK_HOURS, SettingKeys.PRINT_COPIES, SettingKeys.GST_TYPE, SettingKeys.QR_FLAG),
            errors.keys,
        )
        assertEquals("Must be at most 28", errors[SettingKeys.GST_PCT])
        assertEquals("Must be at least 1", errors[SettingKeys.BLOCK_HOURS])
        assertEquals("50", row(SettingKeys.FIRST_BLOCK_RATE).settingValue)
        assertTrue(logs().isEmpty())
    }

    @Test
    fun `setting cannot change while its depends_on switch is off`() = runBlocking {
        env.loginOk("admin", "Admin@123")
        env.settings.updateSettings(mapOf(SettingKeys.GST_FLAG to "N"))
        assertEquals("Turn on GST applicable first", errorsOf(mapOf(SettingKeys.GST_PCT to "12"))[SettingKeys.GST_PCT])

        // Switching the parent on in the same save is allowed.
        env.settings.updateSettings(mapOf(SettingKeys.GST_FLAG to "Y", SettingKeys.GST_PCT to "12"))
        assertEquals("12", row(SettingKeys.GST_PCT).settingValue)
    }

    @Test
    fun `report password is stored as a hash and masked in the log`() = runBlocking {
        env.loginOk("admin", "Admin@123")
        assertEquals("Set a password", errorsOf(mapOf(SettingKeys.REPORT_PWD_FLAG to "Y"))[SettingKeys.REPORT_PWD])

        env.settings.updateSettings(mapOf(SettingKeys.REPORT_PWD_FLAG to "Y", SettingKeys.REPORT_PWD to "Rep@2026"))
        val stored = row(SettingKeys.REPORT_PWD).settingValue
        assertFalse(stored.contains("Rep@2026"))
        assertTrue(env.hasher.verify("Rep@2026", stored))
        val log = logs().first { it.settingName == SettingKeys.REPORT_PWD }
        assertEquals("", log.oldValue)
        assertEquals("******", log.newValue)

        // An empty password field keeps the current password.
        assertEquals(0, env.settings.updateSettings(mapOf(SettingKeys.REPORT_PWD to "")))
        assertEquals(stored, row(SettingKeys.REPORT_PWD).settingValue)
    }

    @Test
    fun `missing setting rows are added to an existing database`() = runBlocking {
        env.db.openHelper.writableDatabase.execSQL("DELETE FROM md_setting WHERE setting_name = 'PWD_MIN_LENGTH'")
        assertEquals(1, env.seeder.addMissingSettings())
        assertEquals("8", row(SettingKeys.PWD_MIN_LENGTH).settingValue)
        assertEquals(0, env.seeder.addMissingSettings())
    }

    @Test
    fun `screen details of existing rows follow the app, values stay`() = runBlocking {
        env.db.openHelper.writableDatabase.execSQL(
            "UPDATE md_setting SET options = 'A:Auto|N:None', label = 'Old', setting_value = 'N' WHERE setting_name = 'PRINTER_TYPE'"
        )
        val before = row(SettingKeys.PRINTER_TYPE)
        assertEquals(0, env.seeder.addMissingSettings())
        val after = row(SettingKeys.PRINTER_TYPE)
        assertTrue(after.options!!.contains("B:Bluetooth"))
        assertEquals("Printer", after.label)
        assertEquals("N", after.settingValue)
        assertEquals("N", after.syncFlag)
        assertEquals(before.localVersion + 1, after.localVersion)
        // Nothing left to change: no new version.
        env.seeder.addMissingSettings()
        assertEquals(after.localVersion, row(SettingKeys.PRINTER_TYPE).localVersion)
    }

    // ---- Printer choice (Printer setup, every user) ------------------------------------------

    private fun ownRows(userId: Long) = runBlocking {
        env.db.settingDao().getScopedRows(Tenant.CUSTOMER_ID, userId).filter { it.userId == userId }
    }

    @Test
    fun `normal user saves the printer for their own login only`() = runBlocking {
        env.loginOk("user1", "User@123")
        val user1 = env.userDbId("user1")

        assertEquals(2, env.settings.savePrinterChoice(PrinterType.BLUETOOTH, "00:11:22:AA:BB:CC"))

        // Customer-wide rows are untouched; the user got override rows, sync-stamped.
        assertEquals("A", row(SettingKeys.PRINTER_TYPE).settingValue)
        assertEquals("", row(SettingKeys.BT_PRINTER).settingValue)
        val own = ownRows(user1).associateBy { it.settingName }
        assertEquals(setOf(SettingKeys.PRINTER_TYPE, SettingKeys.BT_PRINTER), own.keys)
        assertEquals("B", own.getValue(SettingKeys.PRINTER_TYPE).settingValue)
        assertEquals("00:11:22:AA:BB:CC", own.getValue(SettingKeys.BT_PRINTER).settingValue)
        assertEquals("N", own.getValue(SettingKeys.BT_PRINTER).syncFlag)
        assertEquals(2, logs().count { it.changedBy == "user1" })

        // The user's settings use it at once; the admin still has the default.
        assertEquals("00:11:22:AA:BB:CC", awaitCurrent { it?.btPrinter == "00:11:22:AA:BB:CC" }!!.btPrinter)
        assertEquals("", env.settings.getSettings(env.userDbId("admin")).btPrinter)

        // Saving again updates the same rows (no duplicates) and bumps the version.
        val before = own.getValue(SettingKeys.BT_PRINTER).localVersion
        assertEquals(1, env.settings.savePrinterChoice(PrinterType.BLUETOOTH, "00:11:22:AA:BB:DD"))
        val again = ownRows(user1).filter { it.settingName == SettingKeys.BT_PRINTER }
        assertEquals(1, again.size)
        assertEquals(before + 1, again.single().localVersion)
        // Nothing changed: no write, no log.
        assertEquals(0, env.settings.savePrinterChoice(PrinterType.BLUETOOTH, "00:11:22:AA:BB:DD"))
    }

    @Test
    fun `admin saves the printer as the default for everyone`() = runBlocking {
        env.loginOk("admin", "Admin@123")
        assertEquals(2, env.settings.savePrinterChoice(PrinterType.BLUETOOTH, "00:11:22:AA:BB:CC"))
        assertEquals("B", row(SettingKeys.PRINTER_TYPE).settingValue)
        assertEquals("00:11:22:AA:BB:CC", row(SettingKeys.BT_PRINTER).settingValue)
        assertTrue(ownRows(env.userDbId("admin")).isEmpty())
        assertEquals("00:11:22:AA:BB:CC", env.settings.getSettings(env.userDbId("user1")).btPrinter)
    }

    @Test
    fun `normal user changes Print settings for their own login only`() = runBlocking {
        env.loginOk("user1", "User@123")
        val user1 = env.userDbId("user1")

        assertEquals(2, env.settings.updatePrintSettings(mapOf(SettingKeys.PRINT_COPIES to "2", SettingKeys.QR_FLAG to "N")))
        assertEquals("1", row(SettingKeys.PRINT_COPIES).settingValue)
        assertEquals("Y", row(SettingKeys.QR_FLAG).settingValue)
        assertEquals(setOf(SettingKeys.PRINT_COPIES, SettingKeys.QR_FLAG), ownRows(user1).map { it.settingName }.toSet())

        // The Print tab shows the user's own values with the customer labels.
        val tab = env.settings.observePrintDefinitions().first().associateBy { it.name }
        assertEquals("2", tab.getValue(SettingKeys.PRINT_COPIES).value)
        assertEquals("Print copies", tab.getValue(SettingKeys.PRINT_COPIES).label)
        assertEquals("58", tab.getValue(SettingKeys.PAPER_WIDTH).value)
        assertTrue(tab.values.all { it.group == "P" })

        assertEquals(2, env.settings.getSettings(user1).printCopies)
        assertEquals(1, env.settings.getSettings(env.userDbId("admin")).printCopies)
    }

    @Test
    fun `normal user cannot change other settings through the Print save`() {
        env.loginOk("user1", "User@123")
        assertThrows(NotAuthorizedException::class.java) {
            runBlocking { env.settings.updatePrintSettings(mapOf(SettingKeys.PRINT_COPIES to "2", SettingKeys.FIRST_BLOCK_RATE to "1")) }
        }
        assertTrue(ownRows(env.userDbId("user1")).isEmpty())
        assertTrue(logs().isEmpty())
    }

    @Test
    fun `Print settings from a normal user are validated`() {
        env.loginOk("user1", "User@123")
        val errors = assertThrows(SettingsValidationException::class.java) {
            runBlocking { env.settings.updatePrintSettings(mapOf(SettingKeys.PRINT_COPIES to "5")) }
        }.errors
        assertEquals(setOf(SettingKeys.PRINT_COPIES), errors.keys)
        assertTrue(ownRows(env.userDbId("user1")).isEmpty())
    }

    @Test
    fun `printer choice needs a login`() {
        assertThrows(NotAuthorizedException::class.java) {
            runBlocking { env.settings.savePrinterChoice(PrinterType.BLUETOOTH, "00:11:22:AA:BB:CC") }
        }
        assertEquals("", row(SettingKeys.BT_PRINTER).settingValue)
    }

    // ---- Live settings ---------------------------------------------------------------------

    private fun awaitCurrent(predicate: (AppSettings?) -> Boolean): AppSettings? = runBlocking {
        withTimeout(5_000) { env.settings.currentSettings.first(predicate) }
    }

    @Test
    fun `current settings react at once to a saved change`() = runBlocking {
        assertEquals(1, awaitCurrent { it != null }!!.printCopies)
        env.loginOk("admin", "Admin@123")
        env.settings.updateSettings(mapOf(SettingKeys.PRINT_COPIES to "2"))
        assertEquals(2, awaitCurrent { it?.printCopies == 2 }!!.printCopies)
    }

    @Test
    fun `current settings follow the logged-in user's own rows`() = runBlocking {
        val user1 = env.userDbId("user1")
        env.db.settingDao().insert(
            SettingEntity(
                customerId = Tenant.CUSTOMER_ID, userId = user1, settingName = SettingKeys.REPORT_FLAG,
                settingValue = "N", settingType = "G", dataType = "BOOL",
            )
        )
        assertTrue(awaitCurrent { it != null }!!.reportFlag)
        env.loginOk("user1", "User@123")
        assertFalse(awaitCurrent { it?.reportFlag == false }!!.reportFlag)
        env.auth.logout()
        assertTrue(awaitCurrent { it?.reportFlag == true }!!.reportFlag)
    }
}
