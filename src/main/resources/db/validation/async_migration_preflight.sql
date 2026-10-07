-- Read-only migration/reconciliation inventory. Run only against the explicitly selected target.
SELECT status, COUNT(*) AS plan_count, MIN(send_at) AS oldest_send_at
FROM notify_plan
GROUP BY status
ORDER BY status;

SELECT
  CASE
    WHEN wx_trace_id IS NULL OR wx_trace_id = '' THEN 'not_submitted_or_unknown'
    ELSE 'submitted_waiting_callback'
  END AS pending_kind,
  COUNT(*) AS audit_count,
  MIN(created_at) AS oldest_created_at
FROM content_audit_log
WHERE final_result = 0
GROUP BY pending_kind;

SELECT status, job_type, COUNT(*) AS job_count, MIN(next_run_at) AS oldest_next_run_at
FROM async_job
GROUP BY status, job_type
ORDER BY status, job_type;

SELECT status, topic, COUNT(*) AS event_count, MIN(created_at) AS oldest_created_at
FROM outbox_event
GROUP BY status, topic
ORDER BY status, topic;
