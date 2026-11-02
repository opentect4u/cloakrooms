-- 002 · Users, login sessions and per-user number counters (CLAUDE.md §4, §6, §11).

CREATE TABLE IF NOT EXISTS md_user (
  id                 INT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id        INT UNSIGNED NULL COMMENT 'NULL only for Synergic super admins (S)',
  user_id            VARCHAR(100) NOT NULL COMMENT 'login id, unique across all customers',
  user_name          VARCHAR(100) NOT NULL,
  user_code          VARCHAR(10)  NULL COMMENT 'receipt/bill number prefix, e.g. U03; NULL for S',
  password_hash      VARCHAR(255) NOT NULL,
  user_type          ENUM('S','A','O') NOT NULL,
  allow_flag         ENUM('Y','N') NOT NULL DEFAULT 'Y',
  device_id          VARCHAR(100) NULL,
  session_token      VARCHAR(255) NULL COMMENT 'SHA-256 hash of the active token',
  session_started_at DATETIME     NULL,
  last_login         DATETIME     NULL,
  is_deleted         ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag          ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by         VARCHAR(100) NULL,
  created_at         DATETIME     NULL,
  updated_by         VARCHAR(100) NULL,
  updated_at         DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_user_login (user_id),
  UNIQUE KEY uq_user_code (customer_id, user_code),
  KEY idx_user_customer_updated (customer_id, updated_at),
  CONSTRAINT fk_user_customer FOREIGN KEY (customer_id) REFERENCES md_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Cloud only. One row per login; status A = active, F = force-logged-out, L = logged out.
CREATE TABLE IF NOT EXISTS td_user_session (
  session_id   VARCHAR(36)  NOT NULL,
  customer_id  INT UNSIGNED NULL,
  user_id      INT UNSIGNED NOT NULL COMMENT 'md_user.id',
  device_id    VARCHAR(100) NOT NULL,
  token_hash   CHAR(64)     NOT NULL COMMENT 'SHA-256 hex of the access token',
  refresh_hash CHAR(64)     NULL     COMMENT 'SHA-256 hex of the current refresh token',
  status       ENUM('A','F','L') NOT NULL DEFAULT 'A',
  started_at   DATETIME     NOT NULL,
  last_seen_at DATETIME     NULL,
  ended_at     DATETIME     NULL,
  ended_by     VARCHAR(100) NULL,
  PRIMARY KEY (session_id),
  KEY idx_session_user_status (user_id, status),
  KEY idx_session_customer_status (customer_id, status),
  KEY idx_session_refresh (refresh_hash),
  CONSTRAINT fk_session_customer FOREIGN KEY (customer_id) REFERENCES md_customer (customer_id),
  CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES md_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Receipt (R) / bill (B) number series per user and reset period (D: YYYY-MM-DD, M: YYYY-MM, Y: FY2026-27).
CREATE TABLE IF NOT EXISTS md_user_counter (
  id          INT UNSIGNED NOT NULL AUTO_INCREMENT,
  customer_id INT UNSIGNED NOT NULL,
  user_id     INT UNSIGNED NOT NULL COMMENT 'md_user.id',
  series      ENUM('R','B') NOT NULL,
  period_key  VARCHAR(12)  NOT NULL,
  last_no     INT UNSIGNED NOT NULL DEFAULT 0,
  is_deleted  ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag   ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by  VARCHAR(100) NULL,
  created_at  DATETIME     NULL,
  updated_by  VARCHAR(100) NULL,
  updated_at  DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_counter (user_id, series, period_key),
  KEY idx_counter_customer_updated (customer_id, updated_at),
  CONSTRAINT fk_counter_customer FOREIGN KEY (customer_id) REFERENCES md_customer (customer_id),
  CONSTRAINT fk_counter_user FOREIGN KEY (user_id) REFERENCES md_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
