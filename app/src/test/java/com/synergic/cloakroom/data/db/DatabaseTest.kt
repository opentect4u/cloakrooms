package com.synergic.cloakroom.data.db

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.entity.LuggageInOutEntity
import com.synergic.cloakroom.data.db.entity.SettingEntity
import com.synergic.cloakroom.data.repository.SettingsRepositoryImpl
import com.synergic.cloakroom.data.security.PasswordHasher
import com.synergic.cloakroom.data.seed.DemoSeeder
import com.synergic.cloakroom.data.seed.SeedData
import com.synergic.cloakroom.data.session.SessionManager
import com.synergic.cloakroom.domain.settings.SettingKeys
import com.synergic.cloakroom.domain.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class DatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var seeder: DemoSeeder
    private val hasher = PasswordHasher()
    private val time = TimeSource { Instant.parse("2026-11-02T06:30:00Z") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        db = AppDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
        seeder = DemoSeeder(db, hasher, time)
        runBlocking { seeder.seedIfEmpty() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    // ---- Seed data -------------------------------------------------------------------------

    @Test
    fun `seed creates the demo data once`() = runBlocking {
        assertEquals(1, count("md_customer"))
        assertEquals(2, count("md_user"))
        assertEquals(5, count("md_id_proof"))
        assertEquals(3, count("md_header_footer"))
        assertEquals(3, count("md_shift"))
        assertEquals(SeedData.settings.size, count("md_setting"))

        assertFalse("second call must not seed again", seeder.seedIfEmpty())
        assertEquals(2, count("md_user"))
    }

    @Test
    fun `seeded users have hashed passwords and must change them`() = runBlocking {
        val admin = db.userDao().getByUserId("admin")!!
        val user = db.userDao().getByUserId("user1")!!
        assertEquals("A", admin.userType)
        assertEquals("U01", admin.userCode)
        assertEquals("O", user.userType)
        assertEquals("U02", user.userCode)
        assertTrue(hasher.verify("Admin@123", admin.passwordHash))
        assertTrue(hasher.verify("User@123", user.passwordHash))
        assertEquals("Y", admin.mustChangePwd)
        assertEquals(0, admin.failedAttempts)
        assertEquals("N", admin.syncFlag)
        assertEquals(1, admin.localVersion)
        assertEquals("2026-11-02 06:30:00", admin.createdAt)
    }

    @Test
    fun `reset demo data restores the seed`() = runBlocking {
        db.settingDao().insert(setting(SettingKeys.REPORT_FLAG, "N", userId = 2))
        seeder.resetDemoData()
        assertEquals(SeedData.settings.size, count("md_setting"))
        assertEquals(2, count("md_user"))
    }

    // ---- Settings lookup -------------------------------------------------------------------

    @Test
    fun `settings repository reads customer-wide values`() = runBlocking {
        val repo = SettingsRepositoryImpl(db, SessionManager(), hasher, time, scope)
        val settings = repo.getSettings(userId = null)
        assertTrue(settings.reportFlag)
        assertEquals(1, settings.checkinPrintCopies)
    }

    @Test
    fun `user row overrides customer row`() = runBlocking {
        val user1 = db.userDao().getByUserId("user1")!!.id
        val admin = db.userDao().getByUserId("admin")!!.id
        db.settingDao().insert(setting(SettingKeys.REPORT_FLAG, "N", userId = user1))
        db.settingDao().insert(setting(SettingKeys.CHECKIN_PRINT_COPIES, "3", userId = user1))

        val repo = SettingsRepositoryImpl(db, SessionManager(), hasher, time, scope)
        val forUser1 = repo.getSettings(user1)
        assertFalse(forUser1.reportFlag)
        assertEquals(3, forUser1.checkinPrintCopies)

        val forAdmin = repo.getSettings(admin)
        assertTrue("other users keep the customer value", forAdmin.reportFlag)
        assertEquals(1, forAdmin.checkinPrintCopies)

        assertTrue(repo.getSettings(null).reportFlag)
        assertEquals(3, repo.observeSettings(user1).first().checkinPrintCopies)
    }

    @Test
    fun `unique index rejects a second customer-wide row with the same name`() {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.settingDao().insert(setting(SettingKeys.REPORT_FLAG, "N", userId = null)) }
        }
    }

    @Test
    fun `unique index rejects a second row for the same user`() = runBlocking {
        db.settingDao().insert(setting(SettingKeys.REPORT_FLAG, "N", userId = 2))
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.settingDao().insert(setting(SettingKeys.REPORT_FLAG, "Y", userId = 2)) }
        }
        Unit
    }

    // ---- Triggers and CHECK rules ----------------------------------------------------------

    @Test
    fun `trg_inout_resync resets sync_flag when the bag goes out`() = runBlocking {
        db.luggageDao().insert(luggage("T-1"))
        assertEquals(1, db.luggageDao().markSynced("T-1", pushedVersion = 1))
        assertEquals("Y", db.luggageDao().get("T-1")!!.syncFlag)

        // An out update that does not touch sync_flag itself: only the trigger can reset it.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE td_luggage_in_out SET out_flag = 'Y', date_time_out = '2026-11-02 08:00:00' WHERE trans_id = 'T-1'"
        )
        assertEquals("N", db.luggageDao().get("T-1")!!.syncFlag)
    }

    @Test
    fun `sync job's own update does not fire the trigger`() = runBlocking {
        db.luggageDao().insert(luggage("T-2"))
        db.luggageDao().markSynced("T-2", pushedVersion = 1)
        assertEquals("Y", db.luggageDao().get("T-2")!!.syncFlag)
    }

    @Test
    fun `mark synced is skipped when the row changed after the push`() = runBlocking {
        db.luggageDao().insert(luggage("T-3"))
        val updated = db.luggageDao().markOut("T-3", 2, "dev-2", null, "2026-11-02 09:00:00", "user1")
        assertEquals(1, updated)
        val row = db.luggageDao().get("T-3")!!
        assertEquals(2, row.localVersion)
        assertEquals("Y", row.outFlag)
        assertEquals(0, db.luggageDao().markSynced("T-3", pushedVersion = 1))
        assertEquals("N", db.luggageDao().get("T-3")!!.syncFlag)
    }

    @Test
    fun `bag already out cannot be marked out again`() = runBlocking {
        db.luggageDao().insert(luggage("T-4"))
        assertEquals(1, db.luggageDao().markOut("T-4", 2, "dev-2", null, "2026-11-02 09:00:00", "user1"))
        assertEquals(0, db.luggageDao().markOut("T-4", 1, "dev-1", null, "2026-11-02 10:00:00", "admin"))
        assertEquals("2026-11-02 09:00:00", db.luggageDao().get("T-4")!!.dateTimeOut)
    }

    @Test
    fun `check rule rejects an invalid flag on insert and update`() = runBlocking {
        assertThrows(SQLiteException::class.java) {
            runBlocking { db.luggageDao().insert(luggage("T-5").copy(syncFlag = "X")) }
        }
        db.luggageDao().insert(luggage("T-6"))
        assertThrows(SQLiteException::class.java) {
            db.openHelper.writableDatabase.execSQL("UPDATE td_luggage_in_out SET out_flag = 'Q' WHERE trans_id = 'T-6'")
        }
        assertThrows(SQLiteException::class.java) {
            db.openHelper.writableDatabase.execSQL("UPDATE md_user SET user_type = 'Z' WHERE user_id = 'admin'")
        }
        Unit
    }

    @Test
    fun `all extra schema objects exist`() {
        val names = db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type IN ('trigger','index')")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(names.contains("trg_inout_resync"))
        assertTrue(names.contains("ux_md_setting_scope"))
        assertEquals(SchemaExtras.CHECKS.size * 2, names.count { it.startsWith("chk_") })
    }

    private fun setting(name: String, value: String, userId: Long?) = SettingEntity(
        customerId = Tenant.CUSTOMER_ID,
        userId = userId,
        settingName = name,
        settingValue = value,
        settingType = "G",
        dataType = "TEXT",
    )

    private fun luggage(transId: String) = LuggageInOutEntity(
        transId = transId,
        customerId = Tenant.CUSTOMER_ID,
        receiptNo = "U02-00001-$transId",
        receiptDt = "2026-11-02",
        userIdIn = 2,
        deviceIdIn = "dev-1",
        dateTimeIn = "2026-11-02 06:00:00",
        pasnName = "Test Passenger",
        pasnMobNo = "9876543210",
        pnrNo = "1234567890",
        luggageQty = 2,
        idProofId = 1,
        idNumber = "1234",
    )
}
