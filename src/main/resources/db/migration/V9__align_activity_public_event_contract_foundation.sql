-- Contract foundation for strong conditional writes and independent lifecycle facts.
-- Existing numeric IDs remain stable and are serialized as opaque strings at HTTP boundaries.
ALTER TABLE activity
    ADD COLUMN contract_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    ADD COLUMN lifecycle_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 active,1 ended,2 cancelled',
    ADD COLUMN form_version VARCHAR(128) NULL,
    ADD COLUMN audience_department_ids JSON NULL,
    ADD COLUMN contacts_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
    ADD CONSTRAINT chk_activity_lifecycle_status CHECK (lifecycle_status IN (0, 1, 2));

ALTER TABLE activity
    MODIFY COLUMN title VARCHAR(200) NOT NULL,
    MODIFY COLUMN summary VARCHAR(1000) NULL,
    MODIFY COLUMN organizer VARCHAR(200) NULL,
    MODIFY COLUMN location VARCHAR(300) NULL;

ALTER TABLE exam_info
    ADD COLUMN contract_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    ADD COLUMN lifecycle_status TINYINT NOT NULL DEFAULT 0 COMMENT '0 active,1 ended,2 cancelled',
    ADD COLUMN source_name VARCHAR(200) NULL,
    ADD COLUMN source_url VARCHAR(1000) NULL,
    ADD COLUMN contacts_json JSON NOT NULL DEFAULT (JSON_ARRAY()),
    ADD CONSTRAINT chk_exam_lifecycle_status CHECK (lifecycle_status IN (0, 1, 2));

ALTER TABLE exam_info
    MODIFY COLUMN title VARCHAR(200) NOT NULL,
    MODIFY COLUMN summary VARCHAR(1000) NULL,
    MODIFY COLUMN organizer VARCHAR(200) NULL,
    MODIFY COLUMN location VARCHAR(300) NULL,
    MODIFY COLUMN official_url VARCHAR(1000) NULL;

ALTER TABLE activity_registration
    ADD COLUMN contract_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    ADD COLUMN form_version VARCHAR(128) NULL;

-- Preserve the terminal lifecycle as an independent fact, then normalize publication.
UPDATE activity
SET lifecycle_status = CASE
    WHEN publish_status = 3 OR status = 5 THEN 2
    WHEN status = 4 THEN 1
    ELSE 0
END;

-- Contract publication and lifecycle are independent: cancelled content remains published.
UPDATE activity SET publish_status = 1 WHERE publish_status = 3;

UPDATE exam_info
SET lifecycle_status = CASE
    WHEN publish_status = 3 THEN 2
    WHEN status = 4 THEN 1
    ELSE 0
END;

-- PublicEvent cancellation is a lifecycle fact and must remain publicly readable.
UPDATE exam_info SET publish_status = 1 WHERE publish_status = 3;

UPDATE exam_info
SET source_name = COALESCE(NULLIF(organizer, ''), 'Jualn Campus'),
    source_url = official_url
WHERE source_name IS NULL;

UPDATE activity
SET contacts_json = CASE
    WHEN JSON_VALID(contact_info) AND JSON_TYPE(contact_info) = 'ARRAY'
         AND JSON_LENGTH(contact_info) > 0 THEN JSON_ARRAY(JSON_OBJECT(
        'contactKey', 'primary',
        'name', COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(contact_info, '$[0].name')), ''), '联系人'),
        'contact', COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(contact_info, '$[0].contact')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(contact_info, '$[0].phone')), ''))))
    ELSE JSON_ARRAY(JSON_OBJECT('contactKey', 'primary', 'name', '联系人', 'contact', contact_info))
END
WHERE JSON_LENGTH(contacts_json) = 0 AND NULLIF(TRIM(contact_info), '') IS NOT NULL;

UPDATE exam_info
SET contacts_json = JSON_ARRAY(JSON_OBJECT(
    'contactKey', 'primary',
    'name', COALESCE(NULLIF(contact_name, ''), '联系人'),
    'contact', contact_phone))
WHERE JSON_LENGTH(contacts_json) = 0 AND NULLIF(contact_phone, '') IS NOT NULL;

UPDATE activity SET published_at = COALESCE(published_at, updated_at, created_at)
WHERE publish_status = 1 AND published_at IS NULL;
UPDATE exam_info SET published_at = COALESCE(published_at, updated_at, created_at)
WHERE publish_status IN (1, 3) AND published_at IS NULL;

-- A version is present only after the platform form has first been published.
UPDATE activity
SET form_version = CONCAT('activity-', id, '-form-v1')
WHERE form_schema IS NOT NULL AND publish_status IN (1, 2);

UPDATE activity_registration r
JOIN activity a ON a.id = r.activity_id
SET r.form_version = a.form_version
WHERE r.form_version IS NULL AND a.form_version IS NOT NULL;

ALTER TABLE event_section
    ADD COLUMN section_key VARCHAR(128) NULL,
    ADD COLUMN content_format TINYINT NOT NULL DEFAULT 0 COMMENT '0 plain text,1 markdown';

UPDATE event_section SET section_key = CONCAT('legacy-section-', id) WHERE section_key IS NULL;
ALTER TABLE event_section MODIFY section_key VARCHAR(128) NOT NULL;
CREATE UNIQUE INDEX uk_event_section_local_key ON event_section(target_type, target_id, section_key);

ALTER TABLE event_action ADD COLUMN action_key VARCHAR(128) NULL;
UPDATE event_action SET action_key = CONCAT('legacy-action-', id) WHERE action_key IS NULL;
ALTER TABLE event_action MODIFY action_key VARCHAR(128) NOT NULL;
CREATE UNIQUE INDEX uk_event_action_local_key ON event_action(target_type, target_id, action_key);

ALTER TABLE timeline ADD COLUMN node_key VARCHAR(128) NULL;
UPDATE timeline SET node_key = CONCAT('legacy-node-', id) WHERE node_key IS NULL;
ALTER TABLE timeline MODIFY node_key VARCHAR(128) NOT NULL;
CREATE UNIQUE INDEX uk_timeline_local_key ON timeline(target_type, target_id, node_key);
ALTER TABLE timeline
    MODIFY COLUMN location VARCHAR(300) NULL;

CREATE INDEX idx_activity_contract_public_page
    ON activity(publish_status, lifecycle_status, published_at DESC, id DESC);
CREATE INDEX idx_exam_contract_public_page
    ON exam_info(publish_status, lifecycle_status, published_at DESC, id DESC);
