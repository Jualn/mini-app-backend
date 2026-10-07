CREATE TABLE async_dead_message (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  stream_key VARCHAR(128) NOT NULL,
  group_name VARCHAR(96) NOT NULL,
  record_id VARCHAR(64) NOT NULL,
  message_id CHAR(32) NULL,
  operation_id CHAR(32) NULL,
  topic VARCHAR(96) NULL,
  error_category VARCHAR(24) NOT NULL,
  error_detail VARCHAR(1024) NOT NULL,
  delivery_count INT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_seen_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_async_dead_stream_record (stream_key, group_name, record_id),
  KEY idx_async_dead_recent (last_seen_at, id),
  KEY idx_async_dead_message (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
