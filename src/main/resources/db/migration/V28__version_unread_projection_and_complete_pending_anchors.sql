ALTER TABLE notification_inbox_counter ADD COLUMN read_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE notification ADD KEY idx_notification_subject_occurrence (user_id, type, target_type, target_id);

-- Only not-yet-started, current-generation plans may take their anchor from current authoritative Timeline.
-- Processing/completed historical plans MUST NOT fabricate an old anchor from today's subject.
UPDATE notify_plan p
JOIN timeline t ON t.id = p.timeline_id AND t.target_id = p.source_id
LEFT JOIN activity a ON p.source_type = 1 AND a.id = p.source_id AND t.target_type = 2
LEFT JOIN public_event e ON p.source_type = 2 AND e.id = p.source_id AND t.target_type = 3
SET p.subject_starts_at = t.start_time, p.subject_location = t.location
WHERE p.status = 0 AND p.subject_starts_at IS NULL AND t.start_precision = 2 AND t.start_time IS NOT NULL
  AND ((p.notify_type = 9 AND t.node_type = 'REGISTRATION_END' AND p.generation = a.contract_version)
    OR (p.notify_type = 10 AND t.node_type = 'PUBLIC_EVENT_START' AND p.generation = e.contract_version)
    OR (p.notify_type = 11 AND t.node_type = 'REGISTRATION_END' AND p.generation = e.contract_version));
