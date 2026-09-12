-- Expand only. Legacy tables, IDs, status fields and subscription semantics remain.
ALTER TABLE activity
    ADD COLUMN start_precision TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN end_precision TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN time_description VARCHAR(255) NULL,
    ADD COLUMN registration_start_precision TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN registration_end_precision TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN publish_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 draft,1 published,2 removed,3 cancelled',
    MODIFY COLUMN start_time DATETIME NULL,
    MODIFY COLUMN end_time DATETIME NULL,
    ADD COLUMN registration_start DATETIME NULL,
    ADD COLUMN registration_end DATETIME NULL,
    MODIFY COLUMN content MEDIUMTEXT NOT NULL,
    ADD COLUMN summary VARCHAR(300) NULL,
    ADD COLUMN audience_summary VARCHAR(255) NULL,
    ADD COLUMN registration_mode TINYINT NOT NULL DEFAULT 0 COMMENT '0 unknown,1 none,2 form,3 external,4 form+external',
    ADD COLUMN participant_mode TINYINT NOT NULL DEFAULT 0 COMMENT '0 unknown,1 individual,2 team,3 either',
    ADD COLUMN capacity INT UNSIGNED NULL,
    ADD COLUMN capacity_unit TINYINT NULL COMMENT '1 person,2 team',
    ADD COLUMN cover_attachment_id BIGINT UNSIGNED NULL,
    ADD CONSTRAINT fk_activity_cover FOREIGN KEY (cover_attachment_id)
        REFERENCES media_attachment(id) ON DELETE SET NULL;

ALTER TABLE exam_info
    ADD COLUMN start_precision TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN end_precision TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN time_description VARCHAR(255) NULL,
    ADD COLUMN registration_start_precision TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN registration_end_precision TINYINT NOT NULL DEFAULT 0,
    ADD COLUMN publish_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 draft,1 published,2 removed,3 cancelled',
    ADD COLUMN start_time DATETIME NULL,
    ADD COLUMN end_time DATETIME NULL,
    MODIFY COLUMN content MEDIUMTEXT NOT NULL,
    ADD COLUMN summary VARCHAR(300) NULL,
    ADD COLUMN event_type TINYINT NOT NULL DEFAULT 0 COMMENT '0 other,1 exam,2 competition,3 certification',
    ADD COLUMN edition_label VARCHAR(128) NULL;

ALTER TABLE timeline
    ADD COLUMN node_type VARCHAR(32) NOT NULL DEFAULT 'CUSTOM',
    ADD COLUMN location VARCHAR(255) NULL,
    ADD COLUMN start_precision TINYINT NOT NULL DEFAULT 0 COMMENT '0 unknown,1 date,2 minute',
    ADD COLUMN end_precision TINYINT NOT NULL DEFAULT 0 COMMENT '0 unknown,1 date,2 minute',
    ADD COLUMN time_description VARCHAR(255) NULL,
    MODIFY COLUMN sort_order SMALLINT UNSIGNED NOT NULL DEFAULT 0;

CREATE TABLE event_section (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    target_type TINYINT NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    section_type VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    sort_order SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_section_target (target_type, target_id, sort_order, id),
    CONSTRAINT ck_section_target CHECK (target_type IN (2,3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE event_action (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    target_type TINYINT NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    action_type TINYINT NOT NULL,
    label VARCHAR(128) NOT NULL,
    description TEXT NULL,
    target_value VARCHAR(1024) NULL,
    attachment_id BIGINT UNSIGNED NULL,
    is_required TINYINT NOT NULL DEFAULT 0,
    sort_order SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_action_target (target_type, target_id, sort_order, id),
    KEY idx_action_attachment (attachment_id),
    CONSTRAINT fk_action_attachment FOREIGN KEY (attachment_id)
        REFERENCES media_attachment(id) ON DELETE RESTRICT,
    CONSTRAINT ck_action_target CHECK (target_type IN (2,3)),
    CONSTRAINT ck_action_type CHECK (action_type BETWEEN 1 AND 8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Historical content is read through compatibility fallback until an item is edited.
-- Do not infer registration mode, timestamp precision or managed media ownership.
-- Negative legacy timeline ordering must be checked before running this migration.


-- Exact legacy fact mapping only; datetime precision remains unknown.
UPDATE activity SET registration_end = enroll_deadline,
    publish_status = CASE WHEN deleted_at IS NOT NULL OR status = 7 THEN 2
                          WHEN status = 5 THEN 3 WHEN status IN (2,3,4) THEN 1 ELSE 0 END;
UPDATE exam_info SET start_time = CAST(exam_date AS DATETIME),
    end_time = CAST(exam_date_end AS DATETIME),
    start_precision = CASE WHEN exam_date IS NULL THEN 0 ELSE 1 END,
    end_precision = CASE WHEN exam_date_end IS NULL THEN 0 ELSE 1 END,
    publish_status = CASE WHEN deleted_at IS NOT NULL OR status = 6 THEN 2
                          WHEN status IN (2,3,4) THEN 1 ELSE 0 END;
