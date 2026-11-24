-- Read-only. Run on the explicitly selected target before V33 and code switch.
SELECT @@session.time_zone AS session_time_zone, @@global.time_zone AS global_time_zone,
       @@system_time_zone AS system_time_zone, NOW(3) AS database_now, UTC_TIMESTAMP(3) AS utc_now;
SELECT column_name, column_type, is_nullable
FROM information_schema.columns WHERE table_schema=DATABASE()
  AND ((table_name='timeline' AND column_name IN ('label','description','time_description','location'))
    OR (table_name='notify_plan' AND column_name IN ('title','recipient_scope','recipient_upper_user_id'))
    OR (table_name='notification' AND column_name='title'));
SELECT status, notify_enable, COUNT(*) AS rows_count FROM activity_enrollment GROUP BY status, notify_enable;
SELECT status, COUNT(*) AS rows_count FROM activity_registration GROUP BY status;
SELECT notify_type, recipient_scope, status, subject_starts_at IS NULL AS missing_anchor,
       send_at <= NOW(3) AS overdue, COUNT(*) AS rows_count
FROM notify_plan WHERE source_type=1 GROUP BY notify_type,recipient_scope,status,missing_anchor,overdue;
SELECT job_type, schema_version, status, COUNT(*) AS rows_count FROM async_job
WHERE job_type IN ('notification.plan.fanout','notification.wechat-deliver','business-notification.fanout',
                   'activity.business-notification.fanout') GROUP BY job_type,schema_version,status;
SELECT channel,status,COUNT(*) AS rows_count FROM notification_delivery GROUP BY channel,status;
-- No data rewrite: classify missing anchors, v1 sends and UNKNOWN manually using existing runbook.
