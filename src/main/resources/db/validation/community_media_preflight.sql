-- Read only. Run only against an explicitly authorized environment, not as an automatic Flyway migration.
-- Database rows are reference evidence, not proof of object bytes, ACL/policy, versions or cached copies.
START TRANSACTION READ ONLY;
SELECT @@server_uuid AS server_uuid, DATABASE() AS selected_schema;
SELECT 'POST' AS owner_type,a.id AS attachment_id,a.target_id,a.object_key,a.url,
       r.id AS file_id,r.status AS upload_status,r.declared_kind,r.declared_content_type,r.declared_size_bytes,
       CASE WHEN a.object_key IS NULL THEN 'UNRESOLVED_KEY'
            WHEN r.id IS NULL THEN 'UNRESOLVED_UPLOAD_PROOF'
            WHEN a.object_key LIKE 'private/%' THEN 'PRIVATE_NAMESPACE_VERIFY_CLOUD'
            ELSE 'LEGACY_PUBLIC_REQUIRES_REVIEW' END AS disposition
FROM media_attachment a LEFT JOIN media_upload_record r ON BINARY r.object_key=BINARY a.object_key
WHERE a.target_type=1 ORDER BY a.id;
SELECT c.id AS comment_id,c.target_type AS subject_type,c.target_id,c.image_object_key,c.image_url,
       r.id AS file_id,r.status AS upload_status,r.declared_kind,r.declared_content_type,r.declared_size_bytes,
       CASE WHEN c.image_object_key IS NULL THEN 'UNRESOLVED_KEY'
            WHEN r.id IS NULL THEN 'UNRESOLVED_UPLOAD_PROOF'
            WHEN c.image_object_key LIKE 'private/%' THEN 'PRIVATE_NAMESPACE_VERIFY_CLOUD'
            ELSE 'LEGACY_PUBLIC_REQUIRES_REVIEW' END AS disposition
FROM comment c LEFT JOIN media_upload_record r ON BINARY r.object_key=BINARY c.image_object_key
WHERE c.image_url IS NOT NULL OR c.image_object_key IS NOT NULL ORDER BY c.id;
-- Duplicate references must be resolved before retiring an old public object; never assume exclusive ownership.
SELECT BINARY object_key AS object_key,COUNT(*) AS reference_count FROM (
 SELECT object_key FROM media_attachment WHERE object_key IS NOT NULL
 UNION ALL SELECT image_object_key FROM comment WHERE image_object_key IS NOT NULL
) refs GROUP BY BINARY object_key HAVING COUNT(*)>1;

COMMIT;
