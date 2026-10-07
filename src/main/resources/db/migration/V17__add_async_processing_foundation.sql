CREATE TABLE outbox_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  message_id CHAR(32) NOT NULL,
  topic VARCHAR(96) NOT NULL,
  schema_version SMALLINT UNSIGNED NOT NULL,
  operation_id CHAR(32) NULL,
  aggregate_type VARCHAR(64) NULL,
  aggregate_id VARCHAR(64) NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempt INT UNSIGNED NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NOT NULL,
  lease_owner VARCHAR(96) NULL,
  lease_until DATETIME(3) NULL,
  last_error_category VARCHAR(24) NULL,
  last_error VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at DATETIME(3) NULL,
  dead_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_message_id (message_id),
  KEY idx_outbox_due (status, next_attempt_at, id),
  KEY idx_outbox_lease (status, lease_until, id),
  KEY idx_outbox_published_cleanup (status, published_at, id),
  KEY idx_outbox_dead_cleanup (status, dead_at, id),
  KEY idx_outbox_created (created_at),
  CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE async_job (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  job_type VARCHAR(96) NOT NULL,
  schema_version SMALLINT UNSIGNED NOT NULL,
  operation_id CHAR(32) NULL,
  dedupe_key VARCHAR(160) NULL,
  subject_type VARCHAR(64) NULL,
  subject_id VARCHAR(64) NULL,
  payload JSON NOT NULL,
  status VARCHAR(16) NOT NULL,
  next_run_at DATETIME(3) NOT NULL,
  attempt INT UNSIGNED NOT NULL DEFAULT 0,
  max_attempts INT UNSIGNED NOT NULL,
  lease_owner VARCHAR(96) NULL,
  lease_until DATETIME(3) NULL,
  last_error_category VARCHAR(24) NULL,
  last_error VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  dead_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_dedupe (job_type, dedupe_key),
  KEY idx_job_due (status, next_run_at, id),
  KEY idx_job_lease (status, lease_until, id),
  KEY idx_job_finished (status, finished_at),
  CONSTRAINT chk_async_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'DEAD', 'CANCELLED')),
  CONSTRAINT chk_async_job_attempts CHECK (max_attempts > 0 AND attempt <= max_attempts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE async_event_consumption (
  consumer_name VARCHAR(96) NOT NULL,
  message_id CHAR(32) NOT NULL,
  processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (consumer_name, message_id),
  KEY idx_event_consumption_cleanup (processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE notification
  ADD COLUMN source_key VARCHAR(190) NULL COMMENT 'stable business source used for inbox deduplication' AFTER sender_id,
  ADD UNIQUE KEY uk_notification_source (source_key);
