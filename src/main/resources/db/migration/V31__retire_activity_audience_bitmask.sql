-- Run with every activity writer stopped and a verified pre-migration backup.
-- Structured nonempty IDs are authoritative. Preserve unknown/numeric ResourceIds;
-- this migration does NOT guess department names from legacy positions.
-- The old public converter expanded bits 1..5 to strings "1".."5"; preserve that representation.
CREATE TEMPORARY TABLE activity_audience_v31_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_activity_audience_v31_preflight CHECK (valid = 1)
);

-- Fail before changing activity if stored JSON cannot represent the existing contract.
INSERT INTO activity_audience_v31_guard
SELECT IF(COUNT(*) = 0, 1, 0)
FROM activity
WHERE audience_department_ids IS NOT NULL AND JSON_TYPE(audience_department_ids) <> 'ARRAY';

INSERT INTO activity_audience_v31_guard
SELECT IF(COUNT(*) = 0, 1, 0)
FROM activity a
WHERE EXISTS (
    SELECT 1 FROM JSON_TABLE(a.audience_department_ids, '$[*]' COLUMNS (position FOR ORDINALITY)) j
    WHERE JSON_TYPE(JSON_EXTRACT(a.audience_department_ids, CONCAT('$[', j.position - 1, ']'))) <> 'STRING'
       OR CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(a.audience_department_ids, CONCAT('$[', j.position - 1, ']')))) NOT BETWEEN 1 AND 128
       OR TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.audience_department_ids, CONCAT('$[', j.position - 1, ']')))) = ''
);

INSERT INTO activity_audience_v31_guard
SELECT IF(COUNT(*) = 0, 1, 0)
FROM activity a
WHERE audience_department_ids IS NOT NULL
  AND JSON_LENGTH(audience_department_ids) <> (
    SELECT COUNT(DISTINCT BINARY j.department_id)
    FROM JSON_TABLE(a.audience_department_ids, '$[*]' COLUMNS (department_id VARCHAR(128) PATH '$')) j
  );

-- Unknown bits with no authoritative structured scope must be resolved before dropping the source.
INSERT INTO activity_audience_v31_guard
SELECT IF(COUNT(*) = 0, 1, 0)
FROM activity
WHERE (audience_department_ids IS NULL OR JSON_LENGTH(audience_department_ids) = 0)
  AND (audience_scope & 1) = 0 AND (audience_scope & 192) <> 0;

-- Preserve explicit campus, including the historical zero mask.
UPDATE activity
SET audience_department_ids = JSON_ARRAY(), contract_version = contract_version + 1
WHERE (audience_department_ids IS NULL OR JSON_LENGTH(audience_department_ids) = 0)
  AND (audience_scope = 0 OR (audience_scope & 1) = 1);

-- Preserve the previous public representation instead of inventing canonical aliases.
UPDATE activity
SET audience_department_ids = JSON_MERGE_PRESERVE(
        IF((audience_scope & 2) <> 0, JSON_ARRAY('1'), JSON_ARRAY()),
        IF((audience_scope & 4) <> 0, JSON_ARRAY('2'), JSON_ARRAY()),
        IF((audience_scope & 8) <> 0, JSON_ARRAY('3'), JSON_ARRAY()),
        IF((audience_scope & 16) <> 0, JSON_ARRAY('4'), JSON_ARRAY()),
        IF((audience_scope & 32) <> 0, JSON_ARRAY('5'), JSON_ARRAY())),
    contract_version = contract_version + 1
WHERE (audience_department_ids IS NULL OR JSON_LENGTH(audience_department_ids) = 0)
  AND audience_scope <> 0 AND (audience_scope & 1) = 0;

-- Legacy null was campus in published reads and omitted in admin drafts.
UPDATE activity
SET audience_department_ids = JSON_ARRAY(), contract_version = contract_version + 1
WHERE audience_department_ids IS NULL AND audience_scope IS NULL AND publish_status = 1;

UPDATE activity
SET audience_department_ids = NULL, contract_version = contract_version + 1
WHERE JSON_LENGTH(audience_department_ids) = 0 AND audience_scope IS NULL AND publish_status <> 1;

-- DML precedes destructive DDL. MySQL DDL is not transactionally reversible.
ALTER TABLE activity DROP COLUMN audience_scope;
DROP TEMPORARY TABLE activity_audience_v31_guard;
