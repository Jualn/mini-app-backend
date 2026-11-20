-- First-release preferences have their own fact store. No old overrides, owners or read facts are imported.
CREATE TABLE notification_preference_v1 (
  user_id BIGINT UNSIGNED NOT NULL,
  category VARCHAR(32) NOT NULL,
  channel VARCHAR(32) NOT NULL,
  enabled TINYINT(1) NOT NULL,
  source VARCHAR(24) NOT NULL DEFAULT 'USER_OVERRIDE',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (user_id, category, channel),
  CONSTRAINT chk_notice_v1_category CHECK (category IN ('ACTIVITY','PUBLIC_EVENT','COMMENT','REPLY','LIKE','AUDIT_RESULT','SYSTEM','LEARNING_TASK','POINTS')),
  CONSTRAINT chk_notice_v1_channel CHECK (channel IN ('IN_APP','WECHAT_MINI_PROGRAM','WECHAT_OFFICIAL_ACCOUNT')),
  CONSTRAINT chk_notice_v1_enabled CHECK (enabled IN (0,1)),
  CONSTRAINT chk_notice_v1_source CHECK (source='USER_OVERRIDE')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- New delivery facts remain attributable after content disposal; unknown historical facts stay NULL.
ALTER TABLE notification_delivery
  ADD COLUMN notification_type INT NULL,
  ADD COLUMN preference_category VARCHAR(32) NULL,
  ADD KEY idx_notice_delivery_monitor (created_at, channel, notification_type);
