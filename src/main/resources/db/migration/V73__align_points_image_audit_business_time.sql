-- Stop old/new audit writers during this migration, then deploy the matching provider.
-- Before V73, WechatPointMaterialSafety.reserve was the sole automatic scene-12 writer
-- and explicitly wrote LocalDateTime.now(ZoneOffset.UTC). Other audit scenes and manual
-- decisions use business time. Only that proven legacy write path is converted.
-- Save these rows before applying; do not run this UPDATE outside the versioned migration.
-- Points file/application/ledger, Person and RBAC timestamps retain their UTC semantics.
UPDATE content_audit_log
SET created_at = DATE_ADD(created_at, INTERVAL 8 HOUR)
WHERE target_type = 12 AND audit_source = 1;
