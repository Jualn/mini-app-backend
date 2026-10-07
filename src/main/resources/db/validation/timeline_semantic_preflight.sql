-- Read-only Timeline semantic preflight. Run against the target schema before enabling ReminderPolicy.
-- This file deliberately never infers node_type from label, display order, node position, or schedule kind.

SELECT column_name, column_type, is_nullable, column_default, column_comment
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'timeline'
ORDER BY ordinal_position;

SELECT target_type, node_type, start_precision, end_precision, COUNT(*) AS row_count
FROM timeline
GROUP BY target_type, node_type, start_precision, end_precision
ORDER BY target_type, node_type, start_precision, end_precision;

-- Labels are emitted only for human classification. They are not backfill evidence.
SELECT target_type, node_type, label, COUNT(*) AS row_count
FROM timeline
GROUP BY target_type, node_type, label
ORDER BY target_type, node_type, row_count DESC, label;

-- Unknown/legacy rows remain OTHER/unknown until an authoritative source proves their meaning.
SELECT id, target_type, target_id, node_key, node_type, label,
       start_precision, start_time, end_precision, end_time, location, sort_order
FROM timeline
WHERE node_type = 'OTHER'
   OR node_type NOT IN (
       'ACTIVITY_START', 'PUBLIC_EVENT_START',
       'REGISTRATION_START', 'REGISTRATION_END',
       'MATERIAL_SUBMISSION', 'PRELIMINARY', 'SEMIFINAL', 'FINAL', 'EXAM', 'RESULT',
       'CERTIFICATE_COLLECTION', 'ADMISSION_TICKET', 'OTHER'
   )
ORDER BY target_type, target_id, sort_order, id;

-- These migration-era keys are review candidates only. Do not auto-backfill by key or label.
SELECT id, target_type, target_id, node_key, node_type, label,
       start_precision, start_time, end_precision, end_time
FROM timeline
WHERE node_key IN ('legacy-activity-time', 'legacy-exam-date')
ORDER BY target_type, target_id, id;

-- Subject-specific start semantics must never cross target ownership.
SELECT id, target_type, target_id, node_key, node_type
FROM timeline
WHERE (node_type = 'ACTIVITY_START' AND target_type <> 2)
   OR (node_type = 'PUBLIC_EVENT_START' AND target_type <> 3)
ORDER BY id;

-- Reminder-bearing semantic nodes require an exact machine timestamp.
SELECT id, target_type, target_id, node_key, node_type,
       start_precision, start_time, end_precision, end_time
FROM timeline
WHERE node_type IN ('ACTIVITY_START', 'PUBLIC_EVENT_START', 'REGISTRATION_END')
  AND (start_precision <> 2 OR start_time IS NULL)
ORDER BY target_type, target_id, node_type, id;

-- More than one node for a stable semantic is ambiguous and blocks that rule for the subject.
SELECT target_type, target_id, node_type, COUNT(*) AS semantic_count
FROM timeline
WHERE node_type IN ('ACTIVITY_START', 'PUBLIC_EVENT_START', 'REGISTRATION_END')
GROUP BY target_type, target_id, node_type
HAVING COUNT(*) > 1
ORDER BY target_type, target_id, node_type;

-- Published/active subjects that are not ready for start ReminderPolicy.
SELECT 'ACTIVITY' AS subject_type, a.id AS subject_id,
       SUM(CASE WHEN t.node_type = 'ACTIVITY_START'
                     AND t.start_precision = 2 AND t.start_time IS NOT NULL THEN 1 ELSE 0 END) AS exact_start_count
FROM activity a
LEFT JOIN timeline t ON t.target_type = 2 AND t.target_id = a.id
WHERE a.publish_status = 1 AND a.lifecycle_status = 0 AND a.deleted_at IS NULL
GROUP BY a.id
HAVING exact_start_count <> 1
ORDER BY a.id;

SELECT 'PUBLIC_EVENT' AS subject_type, e.id AS subject_id,
       SUM(CASE WHEN t.node_type = 'PUBLIC_EVENT_START'
                     AND t.start_precision = 2 AND t.start_time IS NOT NULL THEN 1 ELSE 0 END) AS exact_start_count
FROM public_event e
LEFT JOIN timeline t ON t.target_type = 3 AND t.target_id = e.id
WHERE e.publish_status = 1 AND e.lifecycle_status = 0 AND e.deleted_at IS NULL
GROUP BY e.id
HAVING exact_start_count <> 1
ORDER BY e.id;

-- Registration deadline readiness is reported separately; absence means no deadline Reminder.
SELECT target_type, target_id,
       SUM(CASE WHEN node_type = 'REGISTRATION_END'
                     AND start_precision = 2 AND start_time IS NOT NULL THEN 1 ELSE 0 END) AS exact_deadline_count
FROM timeline
WHERE target_type IN (2, 3)
GROUP BY target_type, target_id
HAVING exact_deadline_count > 1
ORDER BY target_type, target_id;

-- Orphans indicate ownership/data cleanup work; no semantic backfill is attempted here.
SELECT t.id, t.target_type, t.target_id, t.node_key, t.node_type
FROM timeline t
LEFT JOIN activity a ON t.target_type = 2 AND a.id = t.target_id AND a.deleted_at IS NULL
LEFT JOIN public_event e ON t.target_type = 3 AND e.id = t.target_id AND e.deleted_at IS NULL
WHERE (t.target_type = 2 AND a.id IS NULL)
   OR (t.target_type = 3 AND e.id IS NULL)
   OR t.target_type NOT IN (2, 3)
ORDER BY t.id;
