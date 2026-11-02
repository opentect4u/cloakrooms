-- 005 · Android app releases (cloud only).

CREATE TABLE IF NOT EXISTS md_app_version (
  id             INT UNSIGNED NOT NULL AUTO_INCREMENT,
  version_code   INT UNSIGNED NOT NULL,
  version_name   VARCHAR(20)  NOT NULL,
  apk_url        VARCHAR(255) NOT NULL,
  mandatory_flag ENUM('Y','N') NOT NULL DEFAULT 'N',
  release_notes  TEXT         NULL,
  is_deleted     ENUM('Y','N') NOT NULL DEFAULT 'N',
  sync_flag      ENUM('Y','N') NOT NULL DEFAULT 'N',
  created_by     VARCHAR(100) NULL,
  created_at     DATETIME     NULL,
  updated_by     VARCHAR(100) NULL,
  updated_at     DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_app_version_code (version_code),
  KEY idx_app_version_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
