ALTER TABLE notification
  ADD COLUMN inbox_generation VARCHAR(16) NOT NULL DEFAULT 'LEGACY'
    COMMENT 'LEGACY until the canonical producer switch; CANONICAL rows require an effective IN_APP delivery'
    AFTER source_key,
  ADD KEY idx_notification_canonical_inbox (user_id, inbox_generation, is_read, created_at, id);

CREATE TABLE notification_preference (
  user_id BIGINT UNSIGNED NOT NULL,
  category VARCHAR(32) NOT NULL,
  channel VARCHAR(32) NOT NULL,
  enabled TINYINT(1) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, category, channel),
  CONSTRAINT chk_notification_preference_category
    CHECK (category IN ('ACTIVITY', 'PUBLIC_EVENT')),
  CONSTRAINT chk_notification_preference_channel
    CHECK (channel IN ('IN_APP', 'WECHAT_MINI_PROGRAM', 'WECHAT_OFFICIAL_ACCOUNT')),
  CONSTRAINT chk_notification_preference_enabled
    CHECK (enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Sparse user overrides for notification category and channel preferences';
