-- Existing snapshots remain readable; a NULL binding is never eligible for a new apply.
-- No task, assignment, submission, completion or review fact is rewritten.
ALTER TABLE learning_task_preview ADD COLUMN task_etag VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NULL;
