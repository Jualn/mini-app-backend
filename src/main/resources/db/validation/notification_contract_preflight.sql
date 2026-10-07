-- READ ONLY. Part A: run on V25 before a quiet-writer V26..V28 rollout.
SELECT inbox_generation, is_read, COUNT(*) AS rows_count
FROM notification GROUP BY inbox_generation, is_read;
SELECT COUNT(*) AS canonical_visible_rows FROM notification n
WHERE n.inbox_generation = 'CANONICAL' AND EXISTS (
 SELECT 1 FROM notification_delivery d
 WHERE d.notification_id = n.id AND d.channel = 'IN_APP' AND d.status = 'DELIVERED');
SELECT notify_type, status, COUNT(*) AS anchorless_plans
FROM notify_plan WHERE notify_type BETWEEN 8 AND 11 AND subject_starts_at IS NULL
GROUP BY notify_type, status;
SELECT job_type, status, COUNT(*) AS jobs_count FROM async_job
WHERE job_type LIKE 'notification.%' GROUP BY job_type, status;

-- Part B: run only after V28; zero anomalies required before opening writers.
SELECT COUNT(*) AS shadow_mismatch FROM notification
WHERE is_read <> (read_at IS NOT NULL);
SELECT COUNT(*) AS visibility_mismatch FROM notification n
WHERE (n.inbox_seq IS NOT NULL) <> (n.inbox_generation = 'LEGACY' OR EXISTS (
 SELECT 1 FROM notification_delivery d
 WHERE d.notification_id = n.id AND d.channel = 'IN_APP' AND d.status = 'DELIVERED'));
SELECT n.user_id, MAX(n.inbox_seq) AS observed_head, c.head_seq
FROM notification n LEFT JOIN notification_inbox_counter c ON c.user_id = n.user_id
WHERE n.inbox_seq IS NOT NULL GROUP BY n.user_id, c.head_seq
HAVING c.head_seq IS NULL OR MAX(n.inbox_seq) > c.head_seq;
SELECT notify_type, status, COUNT(*) AS unresolved_anchorless_plans
FROM notify_plan WHERE notify_type BETWEEN 8 AND 11 AND subject_starts_at IS NULL
GROUP BY notify_type, status;
