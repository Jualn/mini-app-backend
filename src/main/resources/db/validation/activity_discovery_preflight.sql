-- Read-only V30 preflight. Includes drafts/deleted rows because V31 cleans the entire table.
-- Unknown/numeric ResourceIds are preserved; their business mapping is not inferred.
SELECT id, publish_status, deleted_at, audience_scope, audience_department_ids
FROM activity
WHERE audience_department_ids IS NOT NULL AND JSON_TYPE(audience_department_ids) <> 'ARRAY';

SELECT a.id, a.publish_status, a.audience_scope, a.audience_department_ids,
       JSON_EXTRACT(a.audience_department_ids, CONCAT('$[', j.position - 1, ']')) AS stored_id
FROM activity a
JOIN JSON_TABLE(a.audience_department_ids, '$[*]' COLUMNS (position FOR ORDINALITY)) j
WHERE JSON_TYPE(JSON_EXTRACT(a.audience_department_ids, CONCAT('$[', j.position - 1, ']'))) <> 'STRING'
   OR CHAR_LENGTH(JSON_UNQUOTE(JSON_EXTRACT(a.audience_department_ids, CONCAT('$[', j.position - 1, ']')))) NOT BETWEEN 1 AND 128
   OR TRIM(JSON_UNQUOTE(JSON_EXTRACT(a.audience_department_ids, CONCAT('$[', j.position - 1, ']')))) = '';

SELECT a.id, a.audience_department_ids
FROM activity a
WHERE audience_department_ids IS NOT NULL AND JSON_LENGTH(audience_department_ids) <> (
    SELECT COUNT(DISTINCT BINARY j.department_id)
    FROM JSON_TABLE(a.audience_department_ids, '$[*]' COLUMNS (department_id VARCHAR(128) PATH '$')) j
);

SELECT id, audience_scope, audience_department_ids
FROM activity
WHERE (audience_department_ids IS NULL OR JSON_LENGTH(audience_department_ids) = 0)
  AND (audience_scope & 1) = 0 AND (audience_scope & 192) <> 0;

-- Remaining business mapping work (not a structural migration failure).
SELECT a.id, j.department_id
FROM activity a
JOIN JSON_TABLE(a.audience_department_ids, '$[*]' COLUMNS (department_id VARCHAR(128) PATH '$')) j
WHERE BINARY j.department_id NOT IN ('information', 'science', 'finance', 'humanities', 'foundation')
ORDER BY a.id, j.department_id;

SELECT id, audience_scope
FROM activity
WHERE (audience_department_ids IS NULL OR JSON_LENGTH(audience_department_ids) = 0)
  AND audience_scope <> 0 AND (audience_scope & 1) = 0;
