INSERT INTO async_job (
  job_type, schema_version, operation_id, dedupe_key, subject_type, subject_id,
  payload, status, next_run_at, attempt, max_attempts
)
SELECT
  'notification.plan.fanout',
  1,
  NULL,
  CONCAT('plan:', id, ':fanout'),
  'notify-plan',
  CAST(id AS CHAR),
  JSON_OBJECT('planId', id),
  'PENDING',
  send_at,
  0,
  6
FROM notify_plan
WHERE status = 0
ON DUPLICATE KEY UPDATE id = async_job.id;
