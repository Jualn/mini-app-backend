-- Stop and drain all old media cleanup/writer instances before applying and cutting over.
-- URL-only legacy registrations require reviewed object-key backfill; do not infer their owner here.
ALTER TABLE media_upload_record
    ADD COLUMN bound_attachment_id BIGINT UNSIGNED NULL AFTER bound_target_id,
    ADD COLUMN cleanup_started_at DATETIME NULL AFTER cleanup_after,
    ADD KEY idx_media_upload_bound_attachment (bound_attachment_id),
    ADD CONSTRAINT fk_media_upload_bound_attachment
        FOREIGN KEY (bound_attachment_id) REFERENCES media_attachment(id) ON DELETE RESTRICT,
    ADD CONSTRAINT chk_media_upload_binding_owner
        CHECK (bound_attachment_id IS NULL OR (status = 1 AND bound_target_id IS NULL));

-- Preserve known deletion attempts across recovery; uncertain legacy attempts need preflight.
UPDATE media_upload_record SET cleanup_started_at = updated_at
WHERE status = 2 OR retry_count > 0 OR last_error IS NOT NULL;

ALTER TABLE media_attachment
    ADD KEY idx_media_attachment_object_key (object_key),
    ADD KEY idx_media_attachment_url (url);
