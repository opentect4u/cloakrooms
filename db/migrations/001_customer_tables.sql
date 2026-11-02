-- 001 · Tenants, subscriptions and Synergic's billing of tenants (CLAUDE.md §11).
-- All times are UTC. All Y/N columns are ENUM('Y','N').

CREATE TABLE IF NOT EXISTS md_customer (
  customer_id   INT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_name VARCHAR(155) NOT NULL,
  customer_addr VARCHAR(200) NOT NULL,
  state         VARCHAR(50)  NULL,
  pin_code      VARCHAR(10)  NOT NULL,
  mobile_no     VARCHAR(20)  NOT NULL,
  email         VARCHAR(150) NULL,
  renewal_dt    DATE         NULL,
  gst_no        VARCHAR(20)  NULL,
  upi_id        VARCHAR(255) NULL,
  user_limit    SMALLINT UNSIGNED NOT NULL DEFAULT 1,
  active_flag   ENUM('Y','N') NOT NULL DEFAULT 'Y',
  remarks       VARCHAR(255) NULL,
  is_deleted    ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag     ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by    VARCHAR(100) NULL,
  created_at    DATETIME     NULL,
  updated_by    VARCHAR(100) NULL,
  updated_at    DATETIME     NULL,
  PRIMARY KEY (customer_id),
  KEY idx_md_customer_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Cloud only (no sync_flag).
CREATE TABLE IF NOT EXISTS md_customer_registration (
  reg_id      INT UNSIGNED NOT NULL AUTO_INCREMENT,
  reg_no      VARCHAR(30)  NOT NULL,
  customer_id INT UNSIGNED NOT NULL,
  start_date  DATE         NOT NULL,
  end_date    DATE         NOT NULL,
  user_count  SMALLINT UNSIGNED NOT NULL,
  remarks     VARCHAR(255) NULL,
  is_deleted  ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by  VARCHAR(100) NULL,
  created_at  DATETIME     NULL,
  updated_by  VARCHAR(100) NULL,
  updated_at  DATETIME     NULL,
  PRIMARY KEY (reg_id),
  UNIQUE KEY uq_reg_no (reg_no),
  KEY idx_reg_customer_end (customer_id, end_date),
  KEY idx_reg_updated_at (updated_at),
  CONSTRAINT fk_reg_customer FOREIGN KEY (customer_id) REFERENCES md_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Cloud only (no sync_flag).
CREATE TABLE IF NOT EXISTS td_customer_billing (
  payment_id     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id    INT UNSIGNED NOT NULL,
  reg_id         INT UNSIGNED NULL,
  payment_dt     DATE          NOT NULL,
  base_amt       DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  cgst           DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  sgst           DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  igst           DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  net_amt        DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  pay_mode       ENUM('C','U','B') NOT NULL,
  transaction_no VARCHAR(50)  NULL,
  remarks        VARCHAR(255) NULL,
  is_deleted     ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by     VARCHAR(100) NULL,
  created_at     DATETIME     NULL,
  updated_by     VARCHAR(100) NULL,
  updated_at     DATETIME     NULL,
  PRIMARY KEY (payment_id),
  KEY idx_cbill_customer_dt (customer_id, payment_dt),
  KEY idx_cbill_reg (reg_id),
  KEY idx_cbill_updated_at (updated_at),
  CONSTRAINT fk_cbill_customer FOREIGN KEY (customer_id) REFERENCES md_customer (customer_id),
  CONSTRAINT fk_cbill_reg FOREIGN KEY (reg_id) REFERENCES md_customer_registration (reg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
