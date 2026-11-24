-- Read-only target inventory BEFORE V38. Run only against an explicitly selected authorized target.
-- Unknown legacy structures require manual ownership/compatibility analysis; never baseline/repair/drop.
SELECT DATABASE() AS target_schema, VERSION() AS database_version,
       @@session.time_zone AS session_zone, @@global.time_zone AS global_zone, @@system_time_zone AS system_zone;
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT table_name FROM information_schema.tables
 WHERE table_schema=DATABASE() AND (table_name LIKE '%learning%' OR table_name LIKE '%submission%');
SELECT table_name, column_name, column_type FROM information_schema.columns
 WHERE table_schema=DATABASE() AND table_name IN
 ('learning_task','learning_task_assignment','learning_task_submission','learning_task_preview','learning_task_request','learning_task_audit')
 ORDER BY table_name,ordinal_position;
-- Do not assume legacy Submission.kind exists; discover it before inspecting CONFIRMATION counts.
SET @learning_legacy_query = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='learning_task_submission' AND column_name='kind')=1,
 'SELECT kind,COUNT(*) AS row_count FROM learning_task_submission GROUP BY kind',
 'SELECT ''No legacy learning_task_submission.kind column found; other discovered tables still require review'' AS inventory');
PREPARE learning_legacy_inventory FROM @learning_legacy_query;
EXECUTE learning_legacy_inventory;
DEALLOCATE PREPARE learning_legacy_inventory;

-- V85/V86 rollout inventory: preserve old drafts and their title/description correction path.
-- This only inventories configurations, never interprets a generic COS bucket as LearningTask readiness.
SET @learning_private_draft_query = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='learning_task' AND column_name='requirements')=1,
 'SELECT id AS task_id,status,JSON_EXTRACT(requirements,''$.maximumFiles'') AS maximum_files,JSON_EXTRACT(requirements,''$.allowedFileKinds'') AS allowed_file_kinds FROM learning_task WHERE status=''DRAFT'' AND COALESCE(JSON_EXTRACT(requirements,''$.maximumFiles''),0)>0 ORDER BY id',
 'SELECT ''No canonical learning_task.requirements column; resolve ownership before inventory'' AS inventory');
PREPARE learning_private_draft_inventory FROM @learning_private_draft_query;
EXECUTE learning_private_draft_inventory;
DEALLOCATE PREPARE learning_private_draft_inventory;
