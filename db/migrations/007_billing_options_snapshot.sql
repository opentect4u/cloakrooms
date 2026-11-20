-- 007 · Phase 11: billing options snapshotted on each bill (BACKEND_API_REQUESTS A3).
-- The server recomputes every pushed bill with the options the device billed with, so a later
-- settings change never turns older offline bills into AMOUNT_MISMATCH. NULL = bill stored
-- before Phase 11 (defaults: grace 0, GST inclusive, GST on the penalty).
-- DDL auto-commits in MySQL, so every step is guarded and the file can be re-run.

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'td_receipt' AND column_name = 'grace_minutes') = 0,
  'ALTER TABLE td_receipt ADD COLUMN grace_minutes SMALLINT UNSIGNED NULL COMMENT ''GRACE_MINUTES used for this bill'' AFTER next_block_rate',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'td_receipt' AND column_name = 'gst_inclusive') = 0,
  'ALTER TABLE td_receipt ADD COLUMN gst_inclusive ENUM(''Y'',''N'') NULL COMMENT ''GST_INCLUSIVE used for this bill'' AFTER gst_flag',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'td_receipt' AND column_name = 'penalty_gst_flag') = 0,
  'ALTER TABLE td_receipt ADD COLUMN penalty_gst_flag ENUM(''Y'',''N'') NULL COMMENT ''PENALTY_GST_FLAG used for this bill'' AFTER gst_inclusive',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
