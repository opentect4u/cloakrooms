package com.synergic.cloakroom.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.synergic.cloakroom.data.db.dao.AssignShiftDao
import com.synergic.cloakroom.data.db.dao.CustomerDao
import com.synergic.cloakroom.data.db.dao.GstDao
import com.synergic.cloakroom.data.db.dao.RateDao
import com.synergic.cloakroom.data.db.dao.HeaderFooterDao
import com.synergic.cloakroom.data.db.dao.IdProofDao
import com.synergic.cloakroom.data.db.dao.LuggageDao
import com.synergic.cloakroom.data.db.dao.ReceiptDao
import com.synergic.cloakroom.data.db.dao.SettingDao
import com.synergic.cloakroom.data.db.dao.ShiftDao
import com.synergic.cloakroom.data.db.dao.StatusDao
import com.synergic.cloakroom.data.db.dao.UserCounterDao
import com.synergic.cloakroom.data.db.dao.UserDao
import com.synergic.cloakroom.data.db.entity.AssignShiftEntity
import com.synergic.cloakroom.data.db.entity.CustomerEntity
import com.synergic.cloakroom.data.db.entity.GstEntity
import com.synergic.cloakroom.data.db.entity.HeaderFooterEntity
import com.synergic.cloakroom.data.db.entity.IdProofEntity
import com.synergic.cloakroom.data.db.entity.LuggageInOutEntity
import com.synergic.cloakroom.data.db.entity.RateDtlsEntity
import com.synergic.cloakroom.data.db.entity.ReceiptEntity
import com.synergic.cloakroom.data.db.entity.SettingEntity
import com.synergic.cloakroom.data.db.entity.SettingLogEntity
import com.synergic.cloakroom.data.db.entity.ShiftEntity
import com.synergic.cloakroom.data.db.entity.UserCounterEntity
import com.synergic.cloakroom.data.db.entity.UserEntity

@Database(
    version = 5,
    exportSchema = true,
    entities = [
        CustomerEntity::class,
        UserEntity::class,
        IdProofEntity::class,
        RateDtlsEntity::class,
        GstEntity::class,
        HeaderFooterEntity::class,
        ShiftEntity::class,
        AssignShiftEntity::class,
        SettingEntity::class,
        SettingLogEntity::class,
        LuggageInOutEntity::class,
        ReceiptEntity::class,
        UserCounterEntity::class,
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun userDao(): UserDao
    abstract fun idProofDao(): IdProofDao
    abstract fun headerFooterDao(): HeaderFooterDao
    abstract fun shiftDao(): ShiftDao
    abstract fun settingDao(): SettingDao
    abstract fun luggageDao(): LuggageDao
    abstract fun receiptDao(): ReceiptDao
    abstract fun userCounterDao(): UserCounterDao
    abstract fun statusDao(): StatusDao
    abstract fun assignShiftDao(): AssignShiftDao
    abstract fun rateDao(): RateDao
    abstract fun gstDao(): GstDao

    companion object {
        const val NAME = "cloakroom.db"

        /**
         * Version 2: td_receipt.gst_no keeps the GSTIN printed on each bill. Existing GST bills get
         * the customer's current GSTIN (the one they were printed with) and are marked for sync.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE td_receipt ADD COLUMN gst_no TEXT")
                db.execSQL(
                    """UPDATE td_receipt
                       SET gst_no = (SELECT c.gst_no FROM md_customer c WHERE c.customer_id = td_receipt.customer_id),
                           sync_flag = 'N', local_version = local_version + 1
                       WHERE gst_flag = 'Y'
                         AND (SELECT c.gst_no FROM md_customer c WHERE c.customer_id = td_receipt.customer_id) IS NOT NULL"""
                )
                // Triggers and CHECK rules are untouched by ADD COLUMN; re-applying is harmless.
                SchemaExtras.apply(db)
                SchemaExtras.beforeRoomValidation(db)
            }
        }

        /**
         * Version 3: tables follow the server's row shapes (API_GUIDE.md §3–§6). Rows are kept.
         * - md_customer: state, active_flag. md_id_proof: mask_flag (Y for Aadhaar).
         *   md_gst: effective_from. md_header_footer: line_order (current print order).
         * - td_luggage_in_out: device_id_in, id_proof_id, id_number may be empty (a bag checked out
         *   from another device's QR before its check-in synced; backend A8).
         * - td_receipt: hours_charged is now days_charged (same value: blocks charged).
         * - md_rate_dtls: the server's shape (block_hours, first/next_block_rate, effective_from);
         *   the old table was never filled (rates came from md_setting).
         * SQLite on Android 7 cannot rename or loosen columns, so those two tables are rebuilt.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE md_customer ADD COLUMN state TEXT")
                db.execSQL("ALTER TABLE md_customer ADD COLUMN active_flag TEXT NOT NULL DEFAULT 'Y'")
                db.execSQL("ALTER TABLE md_id_proof ADD COLUMN mask_flag TEXT NOT NULL DEFAULT 'N'")
                db.execSQL("UPDATE md_id_proof SET mask_flag = 'Y' WHERE id_proof_name LIKE 'Aadhaar%'")
                db.execSQL("ALTER TABLE md_gst ADD COLUMN effective_from TEXT NOT NULL DEFAULT '2017-07-01'")
                db.execSQL("ALTER TABLE md_header_footer ADD COLUMN line_order INTEGER NOT NULL DEFAULT 0")
                // Keep today's print order (lines were printed by id).
                db.execSQL(
                    """UPDATE md_header_footer SET line_order = (
                           SELECT COUNT(*) FROM md_header_footer o
                           WHERE o.customer_id = md_header_footer.customer_id AND o.line_type = md_header_footer.line_type
                             AND o.id <= md_header_footer.id)"""
                )

                db.execSQL("DROP TABLE md_rate_dtls")
                db.execSQL("CREATE TABLE IF NOT EXISTS `md_rate_dtls` (`rate_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `customer_id` INTEGER NOT NULL, `block_hours` INTEGER NOT NULL DEFAULT 24, `first_block_rate` REAL NOT NULL, `next_block_rate` REAL NOT NULL, `effective_from` TEXT NOT NULL, `is_deleted` TEXT NOT NULL DEFAULT 'N', `sync_flag` TEXT NOT NULL DEFAULT 'N', `local_version` INTEGER NOT NULL DEFAULT 0, `created_by` TEXT, `created_at` TEXT, `updated_by` TEXT, `updated_at` TEXT)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `td_luggage_in_out_new` (`trans_id` TEXT NOT NULL, `customer_id` INTEGER NOT NULL, `receipt_no` TEXT NOT NULL, `receipt_dt` TEXT NOT NULL, `user_id_in` INTEGER NOT NULL, `device_id_in` TEXT, `shift_id_in` INTEGER, `date_time_in` TEXT NOT NULL, `pasn_name` TEXT NOT NULL, `pasn_mob_no` TEXT NOT NULL, `pnr_no` TEXT NOT NULL, `luggage_qty` INTEGER NOT NULL, `id_proof_id` INTEGER, `id_number` TEXT, `user_id_out` INTEGER, `device_id_out` TEXT, `shift_id_out` INTEGER, `date_time_out` TEXT, `out_flag` TEXT NOT NULL DEFAULT 'N', `is_deleted` TEXT NOT NULL DEFAULT 'N', `sync_flag` TEXT NOT NULL DEFAULT 'N', `local_version` INTEGER NOT NULL DEFAULT 0, `created_by` TEXT, `created_at` TEXT, `updated_by` TEXT, `updated_at` TEXT, PRIMARY KEY(`trans_id`))")
                db.execSQL("INSERT INTO td_luggage_in_out_new (trans_id, customer_id, receipt_no, receipt_dt, user_id_in, device_id_in, shift_id_in, date_time_in, pasn_name, pasn_mob_no, pnr_no, luggage_qty, id_proof_id, id_number, user_id_out, device_id_out, shift_id_out, date_time_out, out_flag, is_deleted, sync_flag, local_version, created_by, created_at, updated_by, updated_at) SELECT trans_id, customer_id, receipt_no, receipt_dt, user_id_in, device_id_in, shift_id_in, date_time_in, pasn_name, pasn_mob_no, pnr_no, luggage_qty, id_proof_id, id_number, user_id_out, device_id_out, shift_id_out, date_time_out, out_flag, is_deleted, sync_flag, local_version, created_by, created_at, updated_by, updated_at FROM td_luggage_in_out")
                db.execSQL("DROP TABLE td_luggage_in_out")
                db.execSQL("ALTER TABLE td_luggage_in_out_new RENAME TO td_luggage_in_out")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_td_luggage_in_out_customer_id_receipt_no_receipt_dt` ON `td_luggage_in_out` (`customer_id`, `receipt_no`, `receipt_dt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_td_luggage_in_out_customer_id_date_time_in` ON `td_luggage_in_out` (`customer_id`, `date_time_in`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_td_luggage_in_out_customer_id_pasn_mob_no` ON `td_luggage_in_out` (`customer_id`, `pasn_mob_no`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_td_luggage_in_out_customer_id_pnr_no` ON `td_luggage_in_out` (`customer_id`, `pnr_no`)")

                db.execSQL("CREATE TABLE IF NOT EXISTS `td_receipt_new` (`trans_id` TEXT NOT NULL, `customer_id` INTEGER NOT NULL, `bill_no` TEXT NOT NULL, `bill_dt` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `device_id` TEXT NOT NULL, `shift_id` INTEGER, `luggage_qty` INTEGER NOT NULL, `days_charged` INTEGER NOT NULL, `first_block_rate` REAL NOT NULL DEFAULT 0.0, `next_block_rate` REAL NOT NULL DEFAULT 0.0, `block_hours` INTEGER NOT NULL DEFAULT 0, `base_amt` REAL NOT NULL DEFAULT 0.0, `penal_amt` REAL NOT NULL DEFAULT 0.0, `net_amt` REAL NOT NULL DEFAULT 0.0, `taxable_amt` REAL NOT NULL DEFAULT 0.0, `gst_pct` REAL NOT NULL DEFAULT 0.0, `cgst` REAL NOT NULL DEFAULT 0.0, `sgst` REAL NOT NULL DEFAULT 0.0, `igst` REAL NOT NULL DEFAULT 0.0, `gst_flag` TEXT NOT NULL DEFAULT 'N', `pay_mode` TEXT NOT NULL DEFAULT 'C', `upi_ref` TEXT, `bill_printed` TEXT NOT NULL DEFAULT 'Y', `gst_no` TEXT, `is_deleted` TEXT NOT NULL DEFAULT 'N', `sync_flag` TEXT NOT NULL DEFAULT 'N', `local_version` INTEGER NOT NULL DEFAULT 0, `created_by` TEXT, `created_at` TEXT, `updated_by` TEXT, `updated_at` TEXT, PRIMARY KEY(`trans_id`))")
                db.execSQL("INSERT INTO td_receipt_new (trans_id, customer_id, bill_no, bill_dt, user_id, device_id, shift_id, luggage_qty, days_charged, first_block_rate, next_block_rate, block_hours, base_amt, penal_amt, net_amt, taxable_amt, gst_pct, cgst, sgst, igst, gst_flag, pay_mode, upi_ref, bill_printed, gst_no, is_deleted, sync_flag, local_version, created_by, created_at, updated_by, updated_at) SELECT trans_id, customer_id, bill_no, bill_dt, user_id, device_id, shift_id, luggage_qty, hours_charged, first_block_rate, next_block_rate, block_hours, base_amt, penal_amt, net_amt, taxable_amt, gst_pct, cgst, sgst, igst, gst_flag, pay_mode, upi_ref, bill_printed, gst_no, is_deleted, sync_flag, local_version, created_by, created_at, updated_by, updated_at FROM td_receipt")
                db.execSQL("DROP TABLE td_receipt")
                db.execSQL("ALTER TABLE td_receipt_new RENAME TO td_receipt")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_td_receipt_customer_id_bill_no_bill_dt` ON `td_receipt` (`customer_id`, `bill_no`, `bill_dt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_td_receipt_customer_id_bill_dt` ON `td_receipt` (`customer_id`, `bill_dt`)")

                // The rebuilt tables lost their triggers (trg_inout_resync, CHECK rules): add them again.
                SchemaExtras.apply(db)
                SchemaExtras.beforeRoomValidation(db)
            }
        }

        /**
         * Version 4: td_receipt keeps the options each bill was made with (grace_minutes,
         * gst_inclusive, penalty_gst_flag), sent with the bill so the server recomputes with them.
         * Older bills get the defaults (0 / Y / Y).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE td_receipt ADD COLUMN grace_minutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE td_receipt ADD COLUMN gst_inclusive TEXT NOT NULL DEFAULT 'Y'")
                db.execSQL("ALTER TABLE td_receipt ADD COLUMN penalty_gst_flag TEXT NOT NULL DEFAULT 'Y'")
                SchemaExtras.apply(db)
                SchemaExtras.beforeRoomValidation(db)
            }
        }

        /**
         * Version 5: td_luggage_in_out.extra_lugage_qty, the extra bags stored free of charge
         * (same column name as on the server). Existing bags get 0.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE td_luggage_in_out ADD COLUMN extra_lugage_qty INTEGER NOT NULL DEFAULT 0")
                SchemaExtras.apply(db)
                SchemaExtras.beforeRoomValidation(db)
            }
        }

        /**
         * Migrations from version 1 onward. Add each new one here; never use destructive fallback.
         * Every migration must end with [SchemaExtras.beforeRoomValidation].
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        /**
         * Creates the trigger, CHECK rules and expression index after Room creates the tables, and
         * re-creates the expression index on every open (a migration drops it, see SchemaExtras).
         */
        val CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = SchemaExtras.apply(db)
            override fun onOpen(db: SupportSQLiteDatabase) = SchemaExtras.apply(db)
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .addCallback(CALLBACK)
                .build()

        /** In-memory database for unit tests, with the same callback as the real one. */
        fun buildInMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .addCallback(CALLBACK)
                .allowMainThreadQueries()
                .build()
    }
}
