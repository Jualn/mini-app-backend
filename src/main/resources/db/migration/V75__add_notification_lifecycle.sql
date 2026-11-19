-- No historical timestamps, read facts or inbox positions are rewritten.
ALTER TABLE notification
  ADD COLUMN deleted_at DATETIME(6) NULL,
  ADD KEY idx_notification_retention (created_at, id);

-- Minimal replay evidence survives content disposal. No body, account identifier or target is copied.
CREATE TABLE notification_source_receipt (
  source_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  identity_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  notification_id BIGINT UNSIGNED NOT NULL,
  original_created_at DATETIME NOT NULL,
  disposed_at DATETIME(6) NULL
) ENGINE=InnoDB;

CREATE TABLE notification_delivery_resolution (
  delivery_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  operator_id BIGINT UNSIGNED NOT NULL,
  conclusion VARCHAR(32) NOT NULL,
  evidence_ref VARCHAR(190) NOT NULL,
  resolved_at DATETIME(6) NOT NULL,
  CONSTRAINT chk_notification_resolution CHECK (conclusion IN ('CONFIRMED_DELIVERED','CONFIRMED_FAILED','ACCEPT_UNKNOWN'))
) ENGINE=InnoDB;
