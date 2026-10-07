-- Canonical Activity/PublicEvent time now comes only from timeline nodes.
-- Legacy subject columns remain temporarily for disabled legacy read/write adapters; canonical code no longer reads them.
ALTER TABLE activity
    ADD COLUMN card_timeline_node_key VARCHAR(128) NULL AFTER cover_attachment_id;
ALTER TABLE exam_info
    ADD COLUMN card_timeline_node_key VARCHAR(128) NULL AFTER cover_attachment_id;
ALTER TABLE timeline
    MODIFY COLUMN time_description VARCHAR(500) NULL;

-- Precision 0 was explicitly "unknown" in V5. Preserve its display value as TEXT; do not invent EXACT.
UPDATE timeline
SET time_description = COALESCE(NULLIF(TRIM(time_description), ''),
        CONCAT('历史时间：', DATE_FORMAT(COALESCE(start_time, end_time), '%Y-%m-%d %H:%i:%s'))),
    start_time = NULL,
    end_time = NULL,
    start_precision = 0,
    end_precision = 0
WHERE (start_time IS NOT NULL OR end_time IS NOT NULL) AND start_precision = 0;
UPDATE timeline
SET start_time = end_time,
    start_precision = CASE WHEN end_precision = 1 THEN 1 ELSE 2 END,
    end_time = NULL,
    end_precision = 0
WHERE start_time IS NULL AND end_time IS NOT NULL;
UPDATE timeline
SET end_precision = start_precision
WHERE end_time IS NOT NULL AND end_precision = 0 AND start_precision IN (1, 2);
UPDATE timeline
SET time_description = CONCAT('历史时间：', DATE_FORMAT(start_time, '%Y-%m-%d %H:%i:%s'),
        ' 至 ', DATE_FORMAT(end_time, '%Y-%m-%d %H:%i:%s')),
    start_time = NULL,
    end_time = NULL,
    start_precision = 0,
    end_precision = 0
WHERE end_time IS NOT NULL AND start_precision <> end_precision;
UPDATE timeline
SET time_description = '时间待定'
WHERE start_time IS NULL AND end_time IS NULL AND NULLIF(TRIM(time_description), '') IS NULL;
UPDATE timeline
SET time_description = NULL
WHERE start_time IS NOT NULL;
UPDATE timeline
SET node_type = 'OTHER'
WHERE node_type IS NULL OR node_type = 'CUSTOM';

-- Preserve legacy Activity registration facts with their recorded precision.
INSERT INTO timeline(target_type, target_id, node_key, node_type, label, start_time, start_precision, end_precision, time_description, sort_order)
SELECT 2, a.id, 'legacy-registration-start', 'REGISTRATION_START', '报名开始',
       CASE WHEN a.registration_start_precision IN (1, 2) THEN a.registration_start END,
       CASE WHEN a.registration_start_precision IN (1, 2) THEN a.registration_start_precision ELSE 0 END,
       0,
       CASE WHEN a.registration_start_precision = 0 THEN CONCAT('历史时间：', DATE_FORMAT(a.registration_start, '%Y-%m-%d %H:%i:%s')) END,
       0
FROM activity a
WHERE a.registration_start IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM timeline t WHERE t.target_type = 2 AND t.target_id = a.id AND (t.node_type = 'REGISTRATION_START' OR t.node_key = 'legacy-registration-start'));

INSERT INTO timeline(target_type, target_id, node_key, node_type, label, start_time, start_precision, end_precision, time_description, sort_order)
SELECT 2, a.id, 'legacy-registration-end', 'REGISTRATION_END', '报名截止',
       CASE WHEN a.registration_end_precision IN (1, 2) THEN COALESCE(a.registration_end, a.enroll_deadline) END,
       CASE WHEN a.registration_end_precision IN (1, 2) THEN a.registration_end_precision ELSE 0 END,
       0,
       CASE WHEN a.registration_end_precision = 0 THEN CONCAT('历史时间：', DATE_FORMAT(COALESCE(a.registration_end, a.enroll_deadline), '%Y-%m-%d %H:%i:%s')) END,
       1
FROM activity a
WHERE COALESCE(a.registration_end, a.enroll_deadline) IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM timeline t WHERE t.target_type = 2 AND t.target_id = a.id AND (t.node_type = 'REGISTRATION_END' OR t.node_key = 'legacy-registration-end'));

INSERT INTO timeline(target_type, target_id, node_key, node_type, label, start_time, end_time,
                     start_precision, end_precision, time_description, sort_order)
SELECT 2, a.id, 'legacy-activity-time', 'OTHER', '活动时间',
       CASE WHEN a.end_time IS NULL OR a.end_precision = a.start_precision THEN a.start_time END,
       CASE WHEN a.end_time IS NULL OR a.end_precision = a.start_precision THEN a.end_time END,
       CASE WHEN (a.end_time IS NULL OR a.end_precision = a.start_precision) AND a.start_precision IN (1, 2) THEN a.start_precision ELSE 0 END,
       CASE WHEN a.end_time IS NOT NULL AND a.end_precision = a.start_precision THEN a.start_precision ELSE 0 END,
       CASE WHEN a.end_time IS NOT NULL AND a.end_precision <> a.start_precision
            THEN CONCAT('历史时间：', DATE_FORMAT(a.start_time, '%Y-%m-%d %H:%i:%s'),
                        ' 至 ', DATE_FORMAT(a.end_time, '%Y-%m-%d %H:%i:%s')) END,
       2
FROM activity a
WHERE a.start_time IS NOT NULL AND a.start_precision IN (1, 2)
  AND NOT EXISTS (SELECT 1 FROM timeline t WHERE t.target_type = 2 AND t.target_id = a.id AND t.node_key = 'legacy-activity-time');

-- Preserve PublicEvent registration and calendar-date facts without converting DATE to midnight semantics at HTTP.
INSERT INTO timeline(target_type, target_id, node_key, node_type, label, start_time, start_precision, end_precision, time_description, sort_order)
SELECT 3, e.id, 'legacy-registration-start', 'REGISTRATION_START', '报名开始',
       CASE WHEN e.registration_start_precision IN (1, 2) THEN e.registration_start END,
       CASE WHEN e.registration_start_precision IN (1, 2) THEN e.registration_start_precision ELSE 0 END,
       0,
       CASE WHEN e.registration_start_precision = 0 THEN CONCAT('历史时间：', DATE_FORMAT(e.registration_start, '%Y-%m-%d %H:%i:%s')) END,
       0
FROM exam_info e
WHERE e.registration_start IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM timeline t WHERE t.target_type = 3 AND t.target_id = e.id AND (t.node_type = 'REGISTRATION_START' OR t.node_key = 'legacy-registration-start'));

INSERT INTO timeline(target_type, target_id, node_key, node_type, label, start_time, start_precision, end_precision, time_description, sort_order)
SELECT 3, e.id, 'legacy-registration-end', 'REGISTRATION_END', '报名截止',
       CASE WHEN e.registration_end_precision IN (1, 2) THEN e.registration_end END,
       CASE WHEN e.registration_end_precision IN (1, 2) THEN e.registration_end_precision ELSE 0 END,
       0,
       CASE WHEN e.registration_end_precision = 0 THEN CONCAT('历史时间：', DATE_FORMAT(e.registration_end, '%Y-%m-%d %H:%i:%s')) END,
       1
FROM exam_info e
WHERE e.registration_end IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM timeline t WHERE t.target_type = 3 AND t.target_id = e.id AND (t.node_type = 'REGISTRATION_END' OR t.node_key = 'legacy-registration-end'));

INSERT INTO timeline(target_type, target_id, node_key, node_type, label, start_time, end_time,
                     start_precision, end_precision, sort_order)
SELECT 3, e.id, 'legacy-exam-date', 'EXAM', '考试日期',
       CAST(e.exam_date AS DATETIME), CAST(e.exam_date_end AS DATETIME), 1,
       CASE WHEN e.exam_date_end IS NULL THEN 0 ELSE 1 END, 2
FROM exam_info e
WHERE e.exam_date IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM timeline t WHERE t.target_type = 3 AND t.target_id = e.id AND t.node_type = 'EXAM');

CREATE INDEX idx_timeline_exact_future
    ON timeline(target_type, start_precision, start_time, target_id);

ALTER TABLE timeline
    ADD CONSTRAINT chk_timeline_canonical_schedule CHECK (
        (start_precision = 2 AND start_time IS NOT NULL AND ((end_precision = 0 AND end_time IS NULL) OR (end_precision = 2 AND end_time IS NOT NULL)) AND time_description IS NULL)
        OR (start_precision = 1 AND start_time IS NOT NULL AND ((end_precision = 0 AND end_time IS NULL) OR (end_precision = 1 AND end_time IS NOT NULL)) AND time_description IS NULL)
        OR (start_precision = 0 AND start_time IS NULL AND end_precision = 0 AND end_time IS NULL
            AND NULLIF(TRIM(time_description), '') IS NOT NULL)
    ),
    ADD CONSTRAINT chk_timeline_canonical_range CHECK (end_time IS NULL OR end_time >= start_time);

-- A published platform-registration Activity may not remain online with an ambiguous/non-EXACT window.
UPDATE activity a
SET a.publish_status = 2,
    a.is_pinned = FALSE
WHERE a.publish_status = 1
  AND a.registration_mode IN (2, 4)
  AND (
      (SELECT COUNT(*) FROM timeline t
       WHERE t.target_type = 2 AND t.target_id = a.id AND t.node_type = 'REGISTRATION_END') <> 1
      OR (SELECT COUNT(*) FROM timeline t
          WHERE t.target_type = 2 AND t.target_id = a.id AND t.node_type = 'REGISTRATION_END'
            AND t.start_precision = 2 AND t.start_time IS NOT NULL) <> 1
      OR (SELECT COUNT(*) FROM timeline t
          WHERE t.target_type = 2 AND t.target_id = a.id AND t.node_type = 'REGISTRATION_START') > 1
      OR EXISTS (SELECT 1 FROM timeline t
                 WHERE t.target_type = 2 AND t.target_id = a.id AND t.node_type = 'REGISTRATION_START'
                   AND (t.start_precision <> 2 OR t.start_time IS NULL))
  );
