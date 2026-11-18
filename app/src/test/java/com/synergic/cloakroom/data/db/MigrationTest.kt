package com.synergic.cloakroom.data.db

import android.app.Application
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Upgrades a version-1 database (as it is on phones today, including the trigger, CHECK rules and
 * the md_setting expression index) to the current version.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun createVersion1() {
        helper.createDatabase(dbName, 1).apply {
            SchemaExtras.apply(this) // what AppDatabase.CALLBACK added on the phone
            execSQL(
                """INSERT INTO md_customer (customer_id, customer_name, customer_addr, pin_code, mobile_no, gst_no, user_limit,
                       is_deleted, sync_flag, local_version)
                   VALUES (1, 'Demo', 'Howrah', '711101', '9830012345', '19ABCDE1234F1Z5', 1, 'N', 'N', 1)"""
            )
            execSQL(
                """INSERT INTO md_setting (customer_id, user_id, setting_name, setting_value, setting_type, data_type, sort_order,
                       is_deleted, sync_flag, local_version)
                   VALUES (1, NULL, 'GST_FLAG', 'Y', 'B', 'BOOL', 10, 'N', 'N', 1)"""
            )
            fun bill(id: String, gst: String, synced: String) = execSQL(
                """INSERT INTO td_receipt (trans_id, customer_id, bill_no, bill_dt, user_id, device_id, luggage_qty, hours_charged,
                       first_block_rate, next_block_rate, block_hours, base_amt, penal_amt, net_amt, taxable_amt, gst_pct,
                       cgst, sgst, igst, gst_flag, pay_mode, bill_printed, is_deleted, sync_flag, local_version)
                   VALUES ('$id', 1, 'U02-0000$id', '2026-11-02', 2, 'd', 1, 1, 50, 20, 24, 50, 0, 50, 42.37, 18,
                       3.81, 3.82, 0, '$gst', 'C', 'Y', 'N', '$synced', 1)"""
            )
            bill("1", gst = "Y", synced = "Y")
            bill("2", gst = "N", synced = "Y")
            close()
        }
    }

    private fun billRow(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String) =
        db.query("SELECT gst_no, sync_flag, local_version FROM td_receipt WHERE trans_id = '$id'").use {
            it.moveToFirst()
            Triple(if (it.isNull(0)) null else it.getString(0), it.getString(1), it.getInt(2))
        }

    @Test
    fun `1 to 2 adds the bill GSTIN and keeps every row`() {
        createVersion1()
        val db = helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2)

        // GST bill: GSTIN filled in from the customer and marked for sync.
        assertEquals(Triple("19ABCDE1234F1Z5", "N", 2), billRow(db, "1"))
        // Bill without GST: no GSTIN, untouched.
        assertEquals(Triple(null, "Y", 1), billRow(db, "2"))

        val objects = db.query("SELECT name FROM sqlite_master WHERE type IN ('trigger','index')")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(objects.contains("trg_inout_resync"))
        // Rules for columns added in versions 3 and 4 come with those steps.
        val laterColumns = setOf("active_flag", "mask_flag", "gst_inclusive", "penalty_gst_flag")
        assertEquals(SchemaExtras.CHECKS.count { it.second !in laterColumns } * 2, objects.count { it.startsWith("chk_") })
        // Dropped for Room's check; the app creates it again when it opens the database (next test).
        assertTrue(!objects.contains("ux_md_setting_scope"))
        db.close()
    }

    @Test
    fun `the app opens a migrated database`() = runBlocking {
        createVersion1()
        val app = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .addCallback(AppDatabase.CALLBACK)
            .allowMainThreadQueries()
            .build()
        // Room validates every table against the entities when it opens the file.
        assertEquals("19ABCDE1234F1Z5", app.receiptDao().get("1")!!.gstNo)
        assertNull(app.receiptDao().get("2")!!.gstNo)
        assertEquals("Demo", app.customerDao().get(1)!!.customerName)

        // The unique index is back after opening, and still blocks a second customer-wide row.
        val names = app.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index'")
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue(names.contains("ux_md_setting_scope"))
        assertTrue(
            runCatching {
                app.openHelper.writableDatabase.execSQL(
                    """INSERT INTO md_setting (customer_id, user_id, setting_name, setting_value, setting_type, data_type, sort_order)
                       VALUES (1, NULL, 'GST_FLAG', 'N', 'B', 'BOOL', 10)"""
                )
            }.isFailure
        )

        // Opening again (normal app start) works too.
        app.close()
        Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS).addCallback(AppDatabase.CALLBACK).allowMainThreadQueries().build()
            .apply { assertEquals("Demo", customerDao().get(1)!!.customerName) }
            .close()
    }

    // ---- 2 → 3: server row shapes ----------------------------------------------------------------

    private fun createVersion2() {
        helper.createDatabase(dbName, 2).apply {
            SchemaExtras.apply(this)
            execSQL(
                """INSERT INTO md_customer (customer_id, customer_name, customer_addr, pin_code, mobile_no, gst_no, user_limit,
                       is_deleted, sync_flag, local_version)
                   VALUES (1, 'Demo', 'Howrah', '711101', '9830012345', '19ABCDE1234F1Z5', 1, 'N', 'Y', 1)"""
            )
            execSQL("INSERT INTO md_id_proof (id_proof_id, id_proof_name, is_deleted, sync_flag, local_version) VALUES (1, 'Aadhaar Card', 'N', 'N', 1)")
            execSQL("INSERT INTO md_id_proof (id_proof_id, id_proof_name, is_deleted, sync_flag, local_version) VALUES (2, 'Passport', 'N', 'N', 1)")
            fun line(id: Int, type: String) = execSQL(
                """INSERT INTO md_header_footer (id, customer_id, line_type, narration, in_flag, out_flag, report_flag, is_deleted, sync_flag, local_version)
                   VALUES ($id, 1, '$type', 'line $id', 'Y', 'Y', 'N', 'N', 'N', 1)"""
            )
            line(1, "H"); line(2, "F"); line(3, "H")
            execSQL(
                """INSERT INTO td_luggage_in_out (trans_id, customer_id, receipt_no, receipt_dt, user_id_in, device_id_in, date_time_in,
                       pasn_name, pasn_mob_no, pnr_no, luggage_qty, id_proof_id, id_number, out_flag, is_deleted, sync_flag, local_version)
                   VALUES ('t1', 1, 'U02-00001', '2026-11-02', 2, 'dev-A', '2026-11-02 06:30:00',
                       'Ravi', '9876543210', '4512345678', 2, 1, 'XXXXXXXX9012', 'Y', 'N', 'N', 2)"""
            )
            execSQL(
                """INSERT INTO td_receipt (trans_id, customer_id, bill_no, bill_dt, user_id, device_id, luggage_qty, hours_charged,
                       first_block_rate, next_block_rate, block_hours, base_amt, penal_amt, net_amt, taxable_amt, gst_pct,
                       cgst, sgst, igst, gst_flag, pay_mode, bill_printed, gst_no, is_deleted, sync_flag, local_version)
                   VALUES ('t1', 1, 'U02-00001', '2026-11-03', 2, 'dev-A', 2, 2, 50, 20, 24, 140, 0, 140, 118.64, 18,
                       10.68, 10.68, 0, 'Y', 'C', 'Y', '19ABCDE1234F1Z5', 'N', 'N', 1)"""
            )
            close()
        }
    }

    @Test
    fun `2 to 3 keeps every row and follows the server shapes`() {
        createVersion2()
        val db = helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3)

        db.query("SELECT days_charged, net_amt, gst_no, local_version FROM td_receipt WHERE trans_id = 't1'").use {
            it.moveToFirst()
            assertEquals("hours_charged is now days_charged", 2, it.getInt(0))
            assertEquals(140.0, it.getDouble(1), 0.0)
            assertEquals("19ABCDE1234F1Z5", it.getString(2))
            assertEquals(1, it.getInt(3))
        }
        db.query("SELECT device_id_in, id_proof_id, id_number, receipt_no FROM td_luggage_in_out WHERE trans_id = 't1'").use {
            it.moveToFirst()
            assertEquals("dev-A", it.getString(0))
            assertEquals(1, it.getInt(1))
            assertEquals("XXXXXXXX9012", it.getString(2))
            assertEquals("U02-00001", it.getString(3))
        }
        // A row from another device's QR (backend A8): no device, no ID proof.
        db.execSQL(
            """INSERT INTO td_luggage_in_out (trans_id, customer_id, receipt_no, receipt_dt, user_id_in, date_time_in,
                   pasn_name, pasn_mob_no, pnr_no, luggage_qty, out_flag)
               VALUES ('t2', 1, 'U03-00001', '2026-11-02', 3, '2026-11-02 07:00:00', 'Sita', '9123456780', '', 1, 'Y')"""
        )
        db.query("SELECT mask_flag FROM md_id_proof ORDER BY id_proof_id").use {
            it.moveToNext(); assertEquals("Aadhaar is masked", "Y", it.getString(0))
            it.moveToNext(); assertEquals("N", it.getString(0))
        }
        db.query("SELECT id, line_order FROM md_header_footer ORDER BY id").use {
            val orders = buildList { while (it.moveToNext()) add(it.getInt(0) to it.getInt(1)) }
            assertEquals("print order kept per type", listOf(1 to 1, 2 to 1, 3 to 2), orders)
        }
        db.query("SELECT active_flag FROM md_customer WHERE customer_id = 1").use { it.moveToFirst(); assertEquals("Y", it.getString(0)) }

        val triggers = db.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertTrue("the rebuilt table has its sync trigger again", triggers.contains("trg_inout_resync"))
        // The two Y/N rules of td_receipt's version-4 columns come with the 3 → 4 step.
        val v4Columns = setOf("gst_inclusive", "penalty_gst_flag")
        assertEquals(SchemaExtras.CHECKS.count { it.second !in v4Columns } * 2, triggers.count { it.startsWith("chk_") })
        db.close()
    }

    @Test
    fun `3 to 4 keeps bills and adds the options they were billed with`() {
        helper.createDatabase(dbName, 3).apply {
            SchemaExtras.apply(this)
            execSQL(
                """INSERT INTO td_receipt (trans_id, customer_id, bill_no, bill_dt, user_id, device_id, luggage_qty, days_charged,
                       net_amt, gst_flag, sync_flag, local_version)
                   VALUES ('t1', 1, 'U02-00001', '2026-11-03', 2, 'dev-A', 1, 1, 50, 'Y', 'N', 1)"""
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 4, true, AppDatabase.MIGRATION_3_4)
        db.query("SELECT net_amt, grace_minutes, gst_inclusive, penalty_gst_flag, local_version FROM td_receipt WHERE trans_id = 't1'").use {
            it.moveToFirst()
            assertEquals(50.0, it.getDouble(0), 0.0)
            assertEquals("defaults for older bills", listOf("0", "Y", "Y"), listOf(it.getInt(1).toString(), it.getString(2), it.getString(3)))
            assertEquals(1, it.getInt(4))
        }
        val triggers = db.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(SchemaExtras.CHECKS.size * 2, triggers.count { it.startsWith("chk_") })
        db.close()
    }

    @Test
    fun `4 to 5 keeps bags and adds extra bags as 0`() {
        helper.createDatabase(dbName, 4).apply {
            SchemaExtras.apply(this)
            execSQL(
                """INSERT INTO td_luggage_in_out (trans_id, customer_id, receipt_no, receipt_dt, user_id_in, device_id_in, date_time_in,
                       pasn_name, pasn_mob_no, pnr_no, luggage_qty, out_flag, sync_flag, local_version)
                   VALUES ('t1', 1, 'U02-00001', '2026-11-16', 2, 'dev-A', '2026-11-16 05:00:00', 'Ravi', '9876543210', 'P1', 2, 'N', 'Y', 1)"""
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 5, true, AppDatabase.MIGRATION_4_5)
        db.query("SELECT luggage_qty, extra_lugage_qty, sync_flag, local_version FROM td_luggage_in_out WHERE trans_id = 't1'").use {
            it.moveToFirst()
            assertEquals(2, it.getInt(0))
            assertEquals("older bags have no extra bags", 0, it.getInt(1))
            assertEquals("the row is not re-sent", "Y", it.getString(2))
            assertEquals(1, it.getInt(3))
        }
        db.close()
    }

    @Test
    fun `5 to 6 adds price ranges and advance, older bills collected in full`() {
        helper.createDatabase(dbName, 5).apply {
            SchemaExtras.apply(this)
            execSQL(
                """INSERT INTO td_luggage_in_out (trans_id, customer_id, receipt_no, receipt_dt, user_id_in, device_id_in, date_time_in,
                       pasn_name, pasn_mob_no, pnr_no, luggage_qty, extra_lugage_qty, out_flag, sync_flag, local_version)
                   VALUES ('t1', 1, 'U02-00001', '2026-11-16', 2, 'dev-A', '2026-11-16 05:00:00', 'Ravi', '9876543210', 'P1', 2, 0, 'Y', 'Y', 1),
                          ('t2', 1, 'U02-00002', '2026-11-16', 2, 'dev-A', '2026-11-16 05:00:00', 'Sita', '9876543211', 'P2', 1, 2, 'N', 'Y', 1)"""
            )
            execSQL(
                """INSERT INTO td_receipt (trans_id, customer_id, bill_no, bill_dt, user_id, device_id, luggage_qty, days_charged,
                       net_amt, gst_flag, sync_flag, local_version)
                   VALUES ('t1', 1, 'U02-00001', '2026-11-17', 2, 'dev-A', 2, 1, 100, 'Y', 'Y', 1)"""
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 6, true, AppDatabase.MIGRATION_5_6)
        db.query("SELECT net_amt, adv_amt, bal_amt, rate_slabs, sync_flag FROM td_receipt WHERE trans_id = 't1'").use {
            it.moveToFirst()
            assertEquals(listOf(100.0, 0.0, 100.0), listOf(it.getDouble(0), it.getDouble(1), it.getDouble(2)))
            assertTrue("older bill keeps its two-rate snapshot", it.isNull(3))
            assertEquals("not re-sent", "Y", it.getString(4))
        }
        db.query("SELECT trans_id, adv_amt, adv_hours, sync_flag, local_version FROM td_luggage_in_out ORDER BY trans_id").use {
            it.moveToFirst()
            assertEquals(listOf("0.0", "0", "Y", "1"), listOf(it.getDouble(1).toString(), it.getInt(2).toString(), it.getString(3), it.getInt(4).toString()))
            it.moveToNext()
            assertEquals("a bag with extra bags is sent again", listOf("N", "2"), listOf(it.getString(3), it.getInt(4).toString()))
        }
        // SLABS is now an allowed md_setting.data_type.
        db.execSQL(
            """INSERT INTO md_setting (customer_id, setting_name, setting_value, setting_type, data_type, sync_flag, local_version, is_deleted)
               VALUES (1, 'RATE_SLABS', '[]', 'B', 'SLABS', 'N', 1, 'N')"""
        )
        val triggers = db.query("SELECT name FROM sqlite_master WHERE type = 'trigger'").use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(SchemaExtras.CHECKS.size * 2, triggers.count { it.startsWith("chk_") })
        db.close()
    }

    @Test
    fun `6 to 7 puts back times moved 5 h 30 m early by the server`() {
        helper.createDatabase(dbName, 6).apply {
            SchemaExtras.apply(this)
            // t1: checked in here at 12:38:10 UTC, then replaced by the server's 07:08:10 (wrong).
            // t2: a correct bag (in = created). t3: checked out here, out time moved early.
            execSQL(
                """INSERT INTO td_luggage_in_out (trans_id, customer_id, receipt_no, receipt_dt, user_id_in, device_id_in, date_time_in,
                       pasn_name, pasn_mob_no, pnr_no, luggage_qty, out_flag, date_time_out, sync_flag, local_version, created_at)
                   VALUES ('t1', 1, 'U04-00019', '2026-11-18', 2, 'dev-A', '2026-11-18 07:08:10', 'A', '9876543210', '467GH', 1, 'N', NULL, 'Y', 1, '2026-11-18 12:38:10'),
                          ('t2', 1, 'U04-00020', '2026-11-18', 2, 'dev-A', '2026-11-18 12:40:00', 'B', '9876543211', 'P2', 1, 'N', NULL, 'Y', 1, '2026-11-18 12:40:00'),
                          ('t3', 1, 'U04-00021', '2026-11-18', 2, 'dev-A', '2026-11-18 10:00:00', 'C', '9876543212', 'P3', 1, 'Y', '2026-11-18 06:00:00', 'Y', 1, '2026-11-18 10:00:00')"""
            )
            execSQL(
                """INSERT INTO td_receipt (trans_id, customer_id, bill_no, bill_dt, user_id, device_id, luggage_qty, days_charged,
                       net_amt, gst_flag, sync_flag, local_version, created_at)
                   VALUES ('t3', 1, 'U04-00001', '2026-11-18', 2, 'dev-A', 1, 1, 50, 'Y', 'Y', 1, '2026-11-18 11:30:00')"""
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(dbName, 7, true, AppDatabase.MIGRATION_6_7)
        val times = db.query("SELECT trans_id, date_time_in, date_time_out FROM td_luggage_in_out ORDER BY trans_id").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getString(2))) }
        }
        assertEquals(
            listOf(
                Triple("t1", "2026-11-18 12:38:10", null),
                Triple("t2", "2026-11-18 12:40:00", null),
                Triple("t3", "2026-11-18 10:00:00", "2026-11-18 11:30:00"),
            ),
            times,
        )
        db.close()
    }

    @Test
    fun `a version-1 phone upgrades straight to the current version`() = runBlocking {
        createVersion1()
        val app = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, dbName)
            .addMigrations(*AppDatabase.MIGRATIONS).addCallback(AppDatabase.CALLBACK).allowMainThreadQueries().build()
        assertEquals(1, app.receiptDao().get("1")!!.daysCharged)
        assertEquals("Demo", app.customerDao().get(1)!!.customerName)
        app.close()
    }
}
