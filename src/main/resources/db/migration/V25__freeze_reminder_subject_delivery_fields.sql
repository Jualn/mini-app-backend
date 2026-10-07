ALTER TABLE notify_plan
  ADD COLUMN subject_starts_at DATETIME(3) NULL
    COMMENT 'Frozen subject start time used by notification delivery adapters' AFTER content,
  ADD COLUMN subject_location VARCHAR(300) NULL
    COMMENT 'Frozen subject location used by notification delivery adapters' AFTER subject_starts_at;

-- Preserve currently scheduled canonical activity-start reminders across the compatible rollout.
-- Rows that cannot be joined remain NULL and therefore stay IN_APP-only instead of reading mutable
-- Activity/Timeline data during external delivery.
UPDATE notify_plan plan
JOIN timeline node ON node.id = plan.timeline_id
SET plan.subject_starts_at = node.start_time,
    plan.subject_location = node.location
WHERE plan.notify_type = 8
  AND plan.subject_starts_at IS NULL;
