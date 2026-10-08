-- Read-only, before V30/cutover. Never infer keys by LIKE, uploader-directory number or registered alone.
-- Page @media_attachment_after_id forward using the last returned id. Confirm deployed COS aliases separately.
SET @media_attachment_after_id = COALESCE(@media_attachment_after_id, 0);
SELECT a.id, a.target_type, a.target_id, a.registered, a.registered_by, a.object_key, a.url,
       r.id AS upload_record_id, r.user_id AS uploader_id, r.target_type AS upload_type,
       r.status AS upload_status, r.bound_target_id, r.cleanup_after, r.retry_count, r.last_error,
       CASE WHEN a.object_key IS NULL THEN 'URL_MAPPING_REQUIRED'
            WHEN r.id IS NULL THEN 'UPLOAD_RECORD_MISSING'
            WHEN r.status = 2 THEN 'CLEANING_DRAIN_AND_COS_CHECK_REQUIRED'
            WHEN r.status = 0 THEN 'PENDING_REVIEW_REQUIRED'
            ELSE 'BOUND_OWNER_REVIEW_REQUIRED' END AS review_category
FROM media_attachment a
LEFT JOIN media_upload_record r ON r.object_key = a.object_key AND BINARY r.object_key = BINARY a.object_key
WHERE a.id > @media_attachment_after_id
  AND ((a.registered = 1 AND a.target_type IS NULL AND a.target_id IS NULL)
       OR (a.target_type IS NOT NULL AND a.target_id IS NOT NULL)
       OR EXISTS (SELECT 1 FROM event_attachment_link l WHERE l.attachment_id = a.id))
ORDER BY a.id LIMIT 100;

-- Duplicate metadata is legal; the repair must preserve all ids and select one stable lifecycle owner.
SELECT object_key, COUNT(*) AS metadata_count, MIN(id) AS first_attachment_id
FROM media_attachment WHERE object_key IS NOT NULL
GROUP BY BINARY object_key, object_key HAVING COUNT(*) > 1 LIMIT 100;
