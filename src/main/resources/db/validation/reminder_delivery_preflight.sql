-- Read-only classification to run before V20/V21 cutover on the intended target.
-- It deliberately does not infer external delivery success from plan/job state.
SELECT status, source_type, COUNT(*) AS row_count
FROM notify_plan
GROUP BY status, source_type
ORDER BY status, source_type;

SELECT COUNT(*) AS legacy_broadcast_plan_count
FROM notify_plan
WHERE source_type = 3 OR source_id IS NULL;

SELECT p.status AS plan_status, j.status AS job_status, COUNT(*) AS row_count
FROM notify_plan p
LEFT JOIN async_job j
  ON j.job_type = 'notification.plan.fanout'
 AND j.dedupe_key = CONCAT('plan:', p.id, ':fanout')
GROUP BY p.status, j.status
ORDER BY p.status, j.status;

SELECT COUNT(*) AS notifications_without_delivery_history
FROM notification;

-- V20 does not backfill notification_delivery. Historical provider outcome is UNKNOWN
-- unless independently supported by provider/runtime evidence.

-- Run the remaining queries only after V21 has expanded the target schema.
-- A non-LEGACY row without an effective IN_APP delivery must never enter inbox set V.
SELECT n.inbox_generation,
       COUNT(*) AS notification_count,
       SUM(CASE WHEN d.notification_id IS NOT NULL THEN 1 ELSE 0 END) AS effective_in_app_count
FROM notification n
LEFT JOIN notification_delivery d
  ON d.notification_id = n.id
 AND d.channel = 'IN_APP'
 AND d.status = 'DELIVERED'
GROUP BY n.inbox_generation
ORDER BY n.inbox_generation;

SELECT n.inbox_generation, n.type, COUNT(*) AS row_count
FROM notification n
GROUP BY n.inbox_generation, n.type
ORDER BY n.inbox_generation, n.type;

-- These values classify current legacy state only. They do not prove when or why a user
-- selected the value and therefore cannot by themselves resolve D3 consent provenance.
SELECT notify_activity_remind, notify_exam_remind, COUNT(*) AS user_count
FROM user_setting
GROUP BY notify_activity_remind, notify_exam_remind
ORDER BY notify_activity_remind, notify_exam_remind;

SELECT category, channel, enabled, COUNT(*) AS override_count
FROM notification_preference
GROUP BY category, channel, enabled
ORDER BY category, channel, enabled;
