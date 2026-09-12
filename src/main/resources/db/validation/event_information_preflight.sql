-- Read-only preflight before V5. Retain output from the intended environment.
SELECT installed_rank, version, description, success
FROM flyway_schema_history ORDER BY installed_rank;
-- Review before converting sort_order to unsigned.
SELECT id, target_type, target_id, sort_order FROM timeline WHERE sort_order < 0;
SELECT target_type, COUNT(*) AS node_count,
       SUM(start_time IS NOT NULL) AS has_start,
       SUM(end_time IS NOT NULL) AS has_end
FROM timeline GROUP BY target_type;
SELECT id FROM activity
WHERE contact_info IS NOT NULL AND contact_info <> '' AND NOT JSON_VALID(contact_info);
SELECT id, audience_scope FROM activity
WHERE audience_scope NOT IN (0,1,63) AND (audience_scope & 1) <> 0;
SELECT id, activity_id, user_id, type, status FROM activity_enrollment WHERE type <> 2;
SELECT m.id, m.target_type, m.target_id
FROM media_attachment m
LEFT JOIN activity a ON m.target_type = 2 AND m.target_id = a.id
LEFT JOIN exam_info e ON m.target_type = 3 AND m.target_id = e.id
WHERE (m.target_type = 2 AND a.id IS NULL) OR (m.target_type = 3 AND e.id IS NULL);
