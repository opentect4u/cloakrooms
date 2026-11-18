package com.synergic.cloakroom.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL objects that Room cannot declare on entities: the sync trigger from the architecture PDF,
 * the CHECK (... IN (...)) rules from the schema sheet, and the expression unique index on
 * md_setting. Applied when the database is created; future migrations that rebuild a table must
 * call [apply] again (every statement is IF NOT EXISTS, so re-running is safe).
 */
object SchemaExtras {

    /** Trigger from the PDF (Case 2), exactly as written there. */
    const val TRG_INOUT_RESYNC = """
        CREATE TRIGGER IF NOT EXISTS trg_inout_resync
        AFTER UPDATE OF user_id_out, date_time_out, out_flag ON td_luggage_in_out
        BEGIN
          UPDATE td_luggage_in_out SET sync_flag = 'N' WHERE trans_id = NEW.trans_id;
        END
    """

    const val UX_SETTING_SCOPE = """
        CREATE UNIQUE INDEX IF NOT EXISTS ux_md_setting_scope
        ON md_setting (customer_id, IFNULL(user_id, 0), setting_name)
    """

    private val YN = listOf("Y", "N")

    private val SYNC_TABLES = listOf(
        "md_customer", "md_user", "md_id_proof", "md_rate_dtls", "md_gst", "md_header_footer",
        "md_shift", "td_assign_shift", "md_setting", "td_setting_log", "td_luggage_in_out",
        "td_receipt", "md_user_counter",
    )

    /** (table, column, allowed values). SQLite CHECK semantics: NULL is allowed. */
    val CHECKS: List<Triple<String, String, List<String>>> =
        SYNC_TABLES.flatMap { listOf(Triple(it, "sync_flag", YN), Triple(it, "is_deleted", YN)) } +
            listOf(
                Triple("md_user", "user_type", listOf("S", "A", "O")),
                Triple("md_user", "allow_flag", YN),
                Triple("md_user", "must_change_pwd", YN),
                Triple("md_customer", "active_flag", YN),
                Triple("md_id_proof", "mask_flag", YN),
                Triple("md_header_footer", "line_type", listOf("H", "F", "L")),
                Triple("md_header_footer", "in_flag", YN),
                Triple("md_header_footer", "out_flag", YN),
                Triple("md_header_footer", "report_flag", YN),
                Triple("md_setting", "setting_type", listOf("G", "B", "P")),
                Triple("md_setting", "data_type", listOf("BOOL", "INT", "DECIMAL", "TEXT", "ENUM", "PASSWORD", "SLABS")),
                Triple("td_luggage_in_out", "out_flag", YN),
                Triple("td_receipt", "gst_flag", YN),
                Triple("td_receipt", "pay_mode", listOf("C", "U")),
                Triple("td_receipt", "bill_printed", YN),
                Triple("td_receipt", "gst_inclusive", YN),
                Triple("td_receipt", "penalty_gst_flag", YN),
                Triple("md_user_counter", "counter_type", listOf("R", "B")),
            )

    /**
     * Room has no CHECK support, so each rule becomes a BEFORE INSERT and a BEFORE UPDATE
     * trigger that aborts the statement, which is what a CHECK constraint does.
     */
    private fun checkTriggers(checks: List<Triple<String, String, List<String>>>): List<String> = checks.flatMap { (table, column, allowed) ->
        val inList = allowed.joinToString(",") { "'$it'" }
        val raise = "SELECT RAISE(ABORT, 'CHECK constraint failed: $table.$column');"
        listOf(
            "CREATE TRIGGER IF NOT EXISTS chk_${table}_${column}_ins BEFORE INSERT ON $table " +
                "WHEN NEW.$column NOT IN ($inList) BEGIN $raise END",
            "CREATE TRIGGER IF NOT EXISTS chk_${table}_${column}_upd BEFORE UPDATE OF $column ON $table " +
                "WHEN NEW.$column NOT IN ($inList) BEGIN $raise END",
        )
    }

    /**
     * Creates the trigger, index and CHECK rules. During an older migration step a column of a
     * later version may not exist yet; its rule is skipped then and created by the later step.
     */
    fun apply(db: SupportSQLiteDatabase) {
        val present = CHECKS.filter { (table, column, _) -> hasColumn(db, table, column) }
        (listOf(TRG_INOUT_RESYNC, UX_SETTING_SCOPE) + checkTriggers(present)).forEach { db.execSQL(it.trimIndent()) }
    }

    private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
        db.query("PRAGMA table_info($table)").use { c ->
            val name = c.getColumnIndex("name")
            generateSequence { if (c.moveToNext()) c.getString(name) else null }.any { it == column }
        }

    /**
     * After a migration Room compares every table with the entities, and it cannot read the
     * expression index on md_setting (the check fails and the app would not start). So each
     * migration drops it as its last step; [AppDatabase.CALLBACK] creates it again on open,
     * right after Room's check.
     */
    fun beforeRoomValidation(db: SupportSQLiteDatabase) {
        db.execSQL("DROP INDEX IF EXISTS ux_md_setting_scope")
    }
}
