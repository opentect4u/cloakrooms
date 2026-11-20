-- 008 · Phase 13: tariff slabs ("Price Range"), advance at check-in, extra (free) bags.
-- DDL auto-commits in MySQL, so every step is guarded and the file can be re-run.

-- md_rate_dtls.rate_slabs: JSON array [{from_hour, to_hour, rate}], contiguous from hour 0.
-- Each started slab adds its rate (per bag); the last slab repeats every (to_hour − from_hour).
-- block_hours / first_block_rate / next_block_rate stay, kept in step by the API, for older apps.
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'md_rate_dtls' AND column_name = 'rate_slabs') = 0,
  'ALTER TABLE md_rate_dtls ADD COLUMN rate_slabs JSON NULL COMMENT ''[{from_hour,to_hour,rate}] authoritative tariff'' AFTER next_block_rate',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Existing two-rate tariffs → slabs with the same bills: one slab when both rates are equal,
-- else [0, B) first rate + [B, 2B) next rate (the last slab repeats). updated_at moves so that
-- devices pull the slabs.
UPDATE md_rate_dtls
   SET rate_slabs = IF(
         first_block_rate = next_block_rate,
         JSON_ARRAY(JSON_OBJECT('from_hour', 0, 'to_hour', block_hours,
                                'rate', CAST(first_block_rate AS CHAR))),
         JSON_ARRAY(JSON_OBJECT('from_hour', 0, 'to_hour', block_hours,
                                'rate', CAST(first_block_rate AS CHAR)),
                    JSON_OBJECT('from_hour', block_hours, 'to_hour', block_hours * 2,
                                'rate', CAST(next_block_rate AS CHAR)))),
       updated_at = UTC_TIMESTAMP(),
       updated_by = 'migration:008'
 WHERE rate_slabs IS NULL;

-- td_receipt.rate_slabs: the slabs the bill was made with (snapshot, NULL = before Phase 13).
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'td_receipt' AND column_name = 'rate_slabs') = 0,
  'ALTER TABLE td_receipt ADD COLUMN rate_slabs JSON NULL COMMENT ''slabs used for this bill'' AFTER next_block_rate',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Advance taken at check-in (ADVANCE_FLAG / ADVANCE_HOURS) and extra bags stored free.
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'td_luggage_in_out' AND column_name = 'adv_amt') = 0,
  'ALTER TABLE td_luggage_in_out ADD COLUMN adv_amt DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''cash taken at check-in'' AFTER luggage_qty',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'td_luggage_in_out' AND column_name = 'adv_hours') = 0,
  'ALTER TABLE td_luggage_in_out ADD COLUMN adv_hours SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''ADVANCE_HOURS at check-in'' AFTER adv_amt',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Column name as built in the app (spelling kept so no mapping is needed).
SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'td_luggage_in_out' AND column_name = 'extra_lugage_qty') = 0,
  'ALTER TABLE td_luggage_in_out ADD COLUMN extra_lugage_qty SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''extra bags, stored free'' AFTER luggage_qty',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'td_receipt' AND column_name = 'adv_amt') = 0,
  'ALTER TABLE td_receipt ADD COLUMN adv_amt DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''advance subtracted on this bill'' AFTER net_amt',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @s = IF(
  (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'td_receipt' AND column_name = 'bal_amt') = 0,
  'ALTER TABLE td_receipt ADD COLUMN bal_amt DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT ''collected at check-out = max(0, net_amt - adv_amt)'' AFTER adv_amt',
  'DO 0');
PREPARE stmt FROM @s;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Bills stored before Phase 13 had no advance: everything was collected at check-out.
UPDATE td_receipt SET bal_amt = net_amt WHERE adv_amt = 0 AND bal_amt = 0 AND net_amt <> 0;
