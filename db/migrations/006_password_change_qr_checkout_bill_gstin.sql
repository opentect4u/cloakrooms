-- 006 · Phase 10: forced password change, check-out of a not-yet-synced bag (QR), GSTIN on bills.
-- DDL auto-commits in MySQL, so every step is guarded / idempotent and the file can be re-run.

-- md_user.must_change_pwd: Y after an admin creates the user or resets the password; the user's
-- token can then only change the password (or log out) until it is changed.
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'md_user' AND column_name = 'must_change_pwd') = 0,
  'ALTER TABLE md_user ADD COLUMN must_change_pwd ENUM(''Y'',''N'') NOT NULL DEFAULT ''N'' AFTER allow_flag',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- A device that checks out a bag from its QR code does not know the check-in device, shift or ID
-- proof. Its row may arrive first; these fields are then filled once by the check-in push.
ALTER TABLE td_luggage_in_out
  MODIFY device_id_in VARCHAR(100) NULL COMMENT 'NULL until the check-in device syncs (QR check-out first)',
  MODIFY id_proof_id  INT UNSIGNED NULL COMMENT 'NULL until the check-in device syncs (QR check-out first)',
  MODIFY id_number    VARCHAR(50)  NULL COMMENT 'last 4 only when md_id_proof.mask_flag = Y';

-- td_receipt.gst_no: the customer's GSTIN stamped by the server when the bill is stored.
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'td_receipt' AND column_name = 'gst_no') = 0,
  'ALTER TABLE td_receipt ADD COLUMN gst_no VARCHAR(20) NULL COMMENT ''md_customer.gst_no when the bill was stored'' AFTER gst_flag',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
