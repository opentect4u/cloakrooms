-- 009 · Store every DATETIME in IST (Asia/Kolkata, UTC+05:30, no DST) instead of UTC.
-- Owner's decision of 2026-11-18: people read the tables directly and expect Indian time.
-- The API to the app does NOT change: devices still send and receive UTC; the server converts
-- at the database boundary (core/db/pool.ts + core/db/dml.ts). DATE columns (receipt_dt,
-- bill_dt, effective_from, …) were already IST calendar dates and are not touched.
--
-- Runs once (schema_migrations). The only DDL comes FIRST (DDL commits implicitly), so all the
-- UPDATEs and the schema_migrations row commit together: everything is converted, or nothing.
-- NULL + INTERVAL stays NULL.
-- DEPLOY TOGETHER WITH THE CODE THAT READS/WRITES IST: an older server still running after this
-- would write UTC next to IST rows.

-- Default of the only column the database fills itself: IST "now" (sessions run at +05:30).
ALTER TABLE td_sync_exception MODIFY created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- The ALTER ended the runner's transaction (implicit commit): open a new one so the UPDATEs and
-- the runner's schema_migrations INSERT commit (or roll back) together.
START TRANSACTION;

UPDATE md_app_version           SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE md_customer              SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE md_customer_registration SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE md_gst                   SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE md_header_footer         SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE md_id_proof              SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE md_rate_dtls             SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE md_setting               SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE md_shift                 SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE md_user                  SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE,
                                    session_started_at = session_started_at + INTERVAL 330 MINUTE,
                                    last_login = last_login + INTERVAL 330 MINUTE;
UPDATE md_user_counter          SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE td_assign_shift          SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE td_customer_billing      SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE td_luggage_in_out        SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE,
                                    date_time_in = date_time_in + INTERVAL 330 MINUTE,
                                    date_time_out = date_time_out + INTERVAL 330 MINUTE;
UPDATE td_receipt               SET created_at = created_at + INTERVAL 330 MINUTE, updated_at = updated_at + INTERVAL 330 MINUTE;
UPDATE td_sync_exception        SET created_at = created_at + INTERVAL 330 MINUTE,
                                    resolved_at = resolved_at + INTERVAL 330 MINUTE;
UPDATE td_user_session          SET started_at = started_at + INTERVAL 330 MINUTE,
                                    last_seen_at = last_seen_at + INTERVAL 330 MINUTE,
                                    ended_at = ended_at + INTERVAL 330 MINUTE;
-- Rows written by the older migrate script (UTC, always ≤ UTC now). On a brand-new database the
-- rows of this same run are already IST (later than UTC now) and are left alone.
UPDATE schema_migrations        SET applied_at = applied_at + INTERVAL 330 MINUTE
 WHERE applied_at <= UTC_TIMESTAMP();

