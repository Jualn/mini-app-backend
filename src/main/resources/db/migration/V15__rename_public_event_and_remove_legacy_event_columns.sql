-- Preserve the last legacy projections before removing duplicated subject facts.
INSERT INTO event_section(target_type, target_id, section_key, title, content, content_format, sort_order)
SELECT 2, a.id, 'legacy-content', '详情', a.content, 0, 0
FROM activity a
WHERE NULLIF(TRIM(a.content), '') IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM event_section s WHERE s.target_type = 2 AND s.target_id = a.id);

INSERT INTO event_section(target_type, target_id, section_key, title, content, content_format, sort_order)
SELECT 3, e.id, 'legacy-content', '详情', e.content, 0, 0
FROM exam_info e
WHERE NULLIF(TRIM(e.content), '') IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM event_section s WHERE s.target_type = 3 AND s.target_id = e.id);

INSERT INTO event_action(target_type, target_id, action_key, action_type, label, description, sort_order)
SELECT 2, a.id, 'legacy-join-method', 8, '参与说明', a.join_method, 0
FROM activity a
WHERE NULLIF(TRIM(a.join_method), '') IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM event_action x WHERE x.target_type = 2 AND x.target_id = a.id
                  AND x.action_key = 'legacy-join-method');

INSERT INTO event_action(target_type, target_id, action_key, action_type, label, description, target_value, sort_order)
SELECT 2, a.id, 'legacy-qrcode', 2, '参与二维码', '原活动二维码链接', a.qrcode_url, 1
FROM activity a
WHERE NULLIF(TRIM(a.qrcode_url), '') IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM event_action x WHERE x.target_type = 2 AND x.target_id = a.id
                  AND x.action_key = 'legacy-qrcode');

UPDATE activity
SET capacity = COALESCE(capacity, registration_limit, max_participants),
    capacity_unit = COALESCE(capacity_unit,
        CASE WHEN COALESCE(registration_limit, max_participants) IS NULL THEN NULL ELSE 1 END);

UPDATE activity
SET contacts_json = CASE
    WHEN JSON_VALID(contact_info) AND JSON_TYPE(contact_info) = 'ARRAY'
         AND JSON_LENGTH(contact_info) > 0 THEN JSON_ARRAY(JSON_OBJECT(
        'contactKey', 'primary',
        'name', COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(contact_info, '$[0].name')), ''), '联系人'),
        'contact', COALESCE(NULLIF(JSON_UNQUOTE(JSON_EXTRACT(contact_info, '$[0].contact')), ''),
                           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(contact_info, '$[0].phone')), ''))))
    ELSE JSON_ARRAY(JSON_OBJECT('contactKey', 'primary', 'name', '联系人', 'contact', contact_info))
END
WHERE JSON_LENGTH(contacts_json) = 0 AND NULLIF(TRIM(contact_info), '') IS NOT NULL;

UPDATE exam_info
SET source_name = COALESCE(NULLIF(source_name, ''), NULLIF(organizer, ''), 'Jualn Campus'),
    source_url = COALESCE(source_url, official_url)
WHERE source_name IS NULL OR source_name = '' OR source_url IS NULL;

UPDATE exam_info
SET contacts_json = JSON_ARRAY(JSON_OBJECT(
    'contactKey', 'primary',
    'name', COALESCE(NULLIF(contact_name, ''), '联系人'),
    'contact', contact_phone))
WHERE JSON_LENGTH(contacts_json) = 0 AND NULLIF(contact_phone, '') IS NOT NULL;

ALTER TABLE activity
    DROP INDEX idx_status_start,
    DROP INDEX idx_category_status,
    DROP INDEX idx_search,
    DROP CHECK chk_activity_registration_limit,
    DROP COLUMN content,
    DROP COLUMN contact_info,
    DROP COLUMN join_method,
    DROP COLUMN qrcode_url,
    DROP COLUMN start_time,
    DROP COLUMN end_time,
    DROP COLUMN enroll_deadline,
    DROP COLUMN max_participants,
    DROP COLUMN registration_limit,
    DROP COLUMN start_precision,
    DROP COLUMN end_precision,
    DROP COLUMN time_description,
    DROP COLUMN registration_start,
    DROP COLUMN registration_end,
    DROP COLUMN registration_start_precision,
    DROP COLUMN registration_end_precision;

ALTER TABLE activity
    DROP COLUMN status,
    DROP COLUMN audit_status,
    DROP COLUMN reject_reason,
    DROP COLUMN is_pinned;

CREATE FULLTEXT INDEX idx_activity_search ON activity(title, summary, organizer) WITH PARSER ngram;

ALTER TABLE exam_info
    DROP INDEX idx_category_status,
    DROP INDEX idx_registration_end,
    DROP INDEX idx_exam_date,
    DROP COLUMN category,
    DROP COLUMN content,
    DROP COLUMN registration_start,
    DROP COLUMN registration_end,
    DROP COLUMN exam_date,
    DROP COLUMN exam_date_end,
    DROP COLUMN status,
    DROP COLUMN audit_status,
    DROP COLUMN reject_reason,
    DROP COLUMN is_pinned,
    DROP COLUMN start_precision,
    DROP COLUMN end_precision,
    DROP COLUMN time_description,
    DROP COLUMN registration_start_precision,
    DROP COLUMN registration_end_precision,
    DROP COLUMN start_time,
    DROP COLUMN end_time,
    DROP COLUMN organizer,
    DROP COLUMN location,
    DROP COLUMN audience_scope,
    DROP COLUMN audience_summary,
    DROP COLUMN contact_name,
    DROP COLUMN contact_phone,
    DROP COLUMN registration_mode,
    DROP COLUMN participant_mode,
    DROP COLUMN capacity,
    DROP COLUMN capacity_unit;

RENAME TABLE exam_info TO public_event;

ALTER TABLE public_event COMMENT = '公共事项主表';
