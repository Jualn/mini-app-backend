-- Read-only. Run against the explicitly selected environment before V32 deployment.
SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity' AND COLUMN_NAME = 'participant_mode';

SELECT publish_status, participant_mode, COUNT(*) AS row_count
FROM activity
WHERE deleted_at IS NULL
GROUP BY publish_status, participant_mode
ORDER BY publish_status, participant_mode;

-- Published unknown/unsupported values require operations review, never inferred defaults.
SELECT id, contract_version, participant_mode, registration_mode, capacity, capacity_unit
FROM activity
WHERE deleted_at IS NULL AND publish_status = 1
  AND (participant_mode IS NULL OR participant_mode NOT IN (1, 2));

SELECT participant_mode, registration_mode, capacity_unit, COUNT(*) AS row_count
FROM activity
WHERE deleted_at IS NULL
GROUP BY participant_mode, registration_mode, capacity_unit;
