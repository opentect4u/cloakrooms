-- 003 · Masters: global (md_id_proof, md_gst) and per customer (rates, header/footer, shifts,
-- shift assignments, settings). Tenant tables index (customer_id, updated_at) for pull.

CREATE TABLE IF NOT EXISTS md_id_proof (
  id_proof_id   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  id_proof_name VARCHAR(50)  NOT NULL,
  mask_flag     ENUM('Y','N') NOT NULL DEFAULT 'N' COMMENT 'Y = store/print last 4 digits only',
  is_deleted    ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag     ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by    VARCHAR(100) NULL,
  created_at    DATETIME     NULL,
  updated_by    VARCHAR(100) NULL,
  updated_at    DATETIME     NULL,
  PRIMARY KEY (id_proof_id),
  KEY idx_id_proof_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS md_gst (
  gst_id         INT UNSIGNED NOT NULL AUTO_INCREMENT,
  cgst_pct       DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  sgst_pct       DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  igst_pct       DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  effective_from DATE         NOT NULL,
  is_deleted     ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag      ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by     VARCHAR(100) NULL,
  created_at     DATETIME     NULL,
  updated_by     VARCHAR(100) NULL,
  updated_at     DATETIME     NULL,
  PRIMARY KEY (gst_id),
  KEY idx_gst_effective (effective_from),
  KEY idx_gst_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Per-bag tariff: first block + each further started block (replaces from_hour/to_hour/rate).
CREATE TABLE IF NOT EXISTS md_rate_dtls (
  rate_id          INT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id      INT UNSIGNED NOT NULL,
  block_hours      INT UNSIGNED  NOT NULL DEFAULT 24,
  first_block_rate DECIMAL(10,2) NOT NULL,
  next_block_rate  DECIMAL(10,2) NOT NULL,
  effective_from   DATE          NOT NULL,
  is_deleted       ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag        ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by       VARCHAR(100) NULL,
  created_at       DATETIME     NULL,
  updated_by       VARCHAR(100) NULL,
  updated_at       DATETIME     NULL,
  PRIMARY KEY (rate_id),
  UNIQUE KEY uq_rate_effective (customer_id, effective_from),
  KEY idx_rate_customer_updated (customer_id, updated_at),
  CONSTRAINT fk_rate_customer FOREIGN KEY (customer_id) REFERENCES md_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS md_header_footer (
  id          INT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id INT UNSIGNED NOT NULL,
  line_type   ENUM('H','F','L') NOT NULL COMMENT 'H header, F footer, L logo',
  line_order  SMALLINT UNSIGNED NOT NULL DEFAULT 0,
  narration   VARCHAR(100) NULL,
  image       MEDIUMBLOB   NULL COMMENT 'monochrome PNG, L rows only',
  in_flag     ENUM('Y','N') NOT NULL DEFAULT 'Y',
  out_flag    ENUM('Y','N') NOT NULL DEFAULT 'Y',
  report_flag ENUM('Y','N') NOT NULL DEFAULT 'N',
  is_deleted  ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag   ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by  VARCHAR(100) NULL,
  created_at  DATETIME     NULL,
  updated_by  VARCHAR(100) NULL,
  updated_at  DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_hf_customer_type_order (customer_id, line_type, line_order),
  KEY idx_hf_customer_updated (customer_id, updated_at),
  CONSTRAINT fk_hf_customer FOREIGN KEY (customer_id) REFERENCES md_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS md_shift (
  shift_id    INT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id INT UNSIGNED NOT NULL,
  shift_name  VARCHAR(50)  NOT NULL,
  from_time   TIME         NOT NULL COMMENT 'local (IST) time',
  to_time     TIME         NOT NULL COMMENT 'local (IST) time; may cross midnight',
  is_deleted  ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag   ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by  VARCHAR(100) NULL,
  created_at  DATETIME     NULL,
  updated_by  VARCHAR(100) NULL,
  updated_at  DATETIME     NULL,
  PRIMARY KEY (shift_id),
  KEY idx_shift_customer_updated (customer_id, updated_at),
  CONSTRAINT fk_shift_customer FOREIGN KEY (customer_id) REFERENCES md_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS td_assign_shift (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id    INT UNSIGNED NOT NULL,
  user_id        INT UNSIGNED NOT NULL COMMENT 'md_user.id',
  shift_id       INT UNSIGNED NOT NULL,
  effective_from DATE         NOT NULL,
  is_deleted     ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag      ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by     VARCHAR(100) NULL,
  created_at     DATETIME     NULL,
  updated_by     VARCHAR(100) NULL,
  updated_at     DATETIME     NULL,
  PRIMARY KEY (id),
  KEY idx_assign_user_effective (user_id, effective_from),
  KEY idx_assign_shift (shift_id),
  KEY idx_assign_customer_updated (customer_id, updated_at),
  CONSTRAINT fk_assign_customer FOREIGN KEY (customer_id) REFERENCES md_customer (customer_id),
  CONSTRAINT fk_assign_user FOREIGN KEY (user_id) REFERENCES md_user (id),
  CONSTRAINT fk_assign_shift FOREIGN KEY (shift_id) REFERENCES md_shift (shift_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- user_id NULL = customer-wide. MySQL treats NULLs as distinct in UNIQUE keys, so the key uses
-- the generated user_key (NULL -> 0) to really enforce one value per (customer, user, name).
CREATE TABLE IF NOT EXISTS md_setting (
  setting_id    INT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id   INT UNSIGNED NOT NULL,
  user_id       INT UNSIGNED NULL COMMENT 'md_user.id; NULL = whole customer',
  user_key      INT UNSIGNED AS (IFNULL(user_id, 0)) STORED,
  setting_name  VARCHAR(50)  NOT NULL,
  setting_value VARCHAR(255) NOT NULL,
  setting_type  ENUM('G','B','P') NOT NULL COMMENT 'G general, B bill, P print',
  is_deleted    ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag     ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by    VARCHAR(100) NULL,
  created_at    DATETIME     NULL,
  updated_by    VARCHAR(100) NULL,
  updated_at    DATETIME     NULL,
  PRIMARY KEY (setting_id),
  UNIQUE KEY uq_setting (customer_id, user_key, setting_name),
  KEY idx_setting_user (user_id),
  KEY idx_setting_customer_updated (customer_id, updated_at),
  CONSTRAINT fk_setting_customer FOREIGN KEY (customer_id) REFERENCES md_customer (customer_id),
  CONSTRAINT fk_setting_user FOREIGN KEY (user_id) REFERENCES md_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
