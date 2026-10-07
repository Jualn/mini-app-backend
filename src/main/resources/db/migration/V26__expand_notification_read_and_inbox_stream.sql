-- Provider-first expand. V27 backfill and writer switch require a quiet notification write boundary.
ALTER TABLE notification
    ADD COLUMN read_at DATETIME(6) NULL,
    ADD COLUMN inbox_seq BIGINT NULL,
    ADD UNIQUE KEY uk_notification_user_inbox_seq (user_id, inbox_seq),
    ADD KEY idx_notification_user_read_seq (user_id, read_at, inbox_seq),
    ADD KEY idx_notification_user_type_seq (user_id, type, inbox_seq);

CREATE TABLE notification_inbox_counter (
    user_id BIGINT NOT NULL PRIMARY KEY,
    head_seq BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
