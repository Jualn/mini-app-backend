-- Additive storage only; operational continuity choices must be explicitly confirmed, never inferred from END.
ALTER TABLE activity
    ADD COLUMN allow_registration_after_start TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Explicit continuous platform admission; omitted means false',
    ADD COLUMN deleted_by BIGINT NULL COMMENT 'Admin account that soft-deleted this activity',
    ADD COLUMN frozen_form_fields JSON NULL COMMENT 'First published registration fields; retained when form temporarily removed',
    ADD COLUMN last_public_summary JSON NULL COMMENT 'Minimal most recent public activity facts; never unpublished edits';
UPDATE activity SET frozen_form_fields = JSON_EXTRACT(form_schema, '$.fields')
WHERE form_version IS NOT NULL AND form_schema IS NOT NULL;
CREATE INDEX idx_activity_registration_user_submitted ON activity_registration(user_id, submitted_at DESC, id DESC);
UPDATE activity SET last_public_summary = JSON_OBJECT('title', title, 'category', category,
    'organizer', organizer, 'primaryLocation', location, 'main', NULL)
WHERE publish_status = 1 AND deleted_at IS NULL;
