-- Operations-only publishing. Retain legacy IDs, target types and audit history.
ALTER TABLE activity
    ADD COLUMN cancelled_at DATETIME NULL,
    ADD COLUMN cancel_reason VARCHAR(255) NULL;
ALTER TABLE exam_info
    ADD COLUMN organizer VARCHAR(128) NULL,
    ADD COLUMN location VARCHAR(255) NULL,
    ADD COLUMN audience_scope TINYINT UNSIGNED NOT NULL DEFAULT 0,
    ADD COLUMN audience_summary VARCHAR(255) NULL,
    ADD COLUMN contact_name VARCHAR(64) NULL,
    ADD COLUMN contact_phone VARCHAR(32) NULL,
    ADD COLUMN registration_mode TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN participant_mode TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN capacity INT UNSIGNED NULL,
    ADD COLUMN capacity_unit TINYINT NULL,
    ADD COLUMN cover_attachment_id BIGINT UNSIGNED NULL,
    ADD COLUMN cancelled_at DATETIME NULL,
    ADD COLUMN cancel_reason VARCHAR(255) NULL,
    ADD CONSTRAINT fk_exam_cover FOREIGN KEY (cover_attachment_id)
        REFERENCES media_attachment(id) ON DELETE SET NULL;
-- Do not automatically publish pending/rejected records or invent historical cancellation times.
