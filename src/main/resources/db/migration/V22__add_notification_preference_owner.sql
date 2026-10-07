ALTER TABLE notification_preference
  ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'USER_OVERRIDE'
    COMMENT 'USER_OVERRIDE or preserved LEGACY_MIGRATION source'
    AFTER enabled,
  ADD CONSTRAINT chk_notification_preference_source
    CHECK (source IN ('USER_OVERRIDE', 'LEGACY_MIGRATION'));

CREATE TABLE notification_preference_owner (
  user_id BIGINT UNSIGNED NOT NULL,
  owner VARCHAR(16) NOT NULL,
  migrated_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id),
  CONSTRAINT chk_notification_preference_owner
    CHECK (owner IN ('CANONICAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Per-user owner switch for canonical notification preferences';
