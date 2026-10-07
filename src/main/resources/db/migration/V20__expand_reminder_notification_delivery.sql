ALTER TABLE notify_plan
  ADD COLUMN timeline_id BIGINT UNSIGNED NULL COMMENT 'Timeline fact that produced this reminder; NULL only for legacy rows' AFTER source_id,
  ADD COLUMN rule_key VARCHAR(64) NULL COMMENT 'Stable reminder rule key; NULL only for legacy rows' AFTER timeline_id,
  ADD COLUMN generation BIGINT UNSIGNED NULL COMMENT 'Subject contract generation; NULL only for legacy rows' AFTER rule_key,
  ADD COLUMN recipient_scope VARCHAR(32) NULL COMMENT 'Stable recipient scope; NULL only for legacy rows' AFTER generation,
  ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at,
  ADD COLUMN completed_at DATETIME(3) NULL AFTER updated_at,
  ADD COLUMN cancelled_at DATETIME(3) NULL AFTER completed_at,
  ADD UNIQUE KEY uk_notify_plan_logical_identity
    (source_type, source_id, timeline_id, rule_key, generation),
  ADD KEY idx_notify_plan_reconcile (source_type, source_id, status, id);

ALTER TABLE notification
  ADD COLUMN content_schema_version SMALLINT UNSIGNED NULL
    COMMENT 'Version of channel-neutral frozen content payload' AFTER content,
  ADD COLUMN content_payload JSON NULL
    COMMENT 'Bounded channel-neutral frozen content payload' AFTER content_schema_version;

CREATE TABLE notification_delivery (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  notification_id BIGINT UNSIGNED NOT NULL,
  channel VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INT UNSIGNED NOT NULL DEFAULT 0,
  provider_message_id VARCHAR(128) NULL,
  result_category VARCHAR(24) NULL,
  provider_error_code VARCHAR(64) NULL,
  last_error_message VARCHAR(512) NULL,
  last_attempt_at DATETIME(3) NULL,
  delivered_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_notification_delivery_channel (notification_id, channel),
  KEY idx_notification_delivery_state (status, updated_at, id),
  CONSTRAINT fk_notification_delivery_notification
    FOREIGN KEY (notification_id) REFERENCES notification(id) ON DELETE CASCADE,
  CONSTRAINT chk_notification_delivery_status
    CHECK (status IN ('PENDING', 'PROCESSING', 'DELIVERED', 'FAILED', 'UNKNOWN', 'SKIPPED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='External channel delivery intent and result for one inbox notification';
