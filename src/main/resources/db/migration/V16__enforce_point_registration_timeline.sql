-- The existing precision-aware timeline storage already distinguishes points from ranges:
-- a point has no end boundary, while a range has both boundaries. Do not rewrite ranges as points.
-- Published platform-registration activities must use EXACT_POINT for their standard boundaries.
UPDATE activity a
SET a.publish_status = 2
WHERE a.publish_status = 1
  AND a.registration_mode IN (2, 4)
  AND (
      (SELECT COUNT(*) FROM timeline t
       WHERE t.target_type = 2
         AND t.target_id = a.id
         AND t.node_type = 'REGISTRATION_END') <> 1
      OR (SELECT COUNT(*) FROM timeline t
          WHERE t.target_type = 2
            AND t.target_id = a.id
            AND t.node_type = 'REGISTRATION_END'
            AND t.start_precision = 2
            AND t.start_time IS NOT NULL
            AND t.end_precision = 0
            AND t.end_time IS NULL) <> 1
      OR (SELECT COUNT(*) FROM timeline t
          WHERE t.target_type = 2
            AND t.target_id = a.id
            AND t.node_type = 'REGISTRATION_START') > 1
      OR EXISTS (
          SELECT 1 FROM timeline t
          WHERE t.target_type = 2
            AND t.target_id = a.id
            AND t.node_type = 'REGISTRATION_START'
            AND (t.start_precision <> 2
                 OR t.start_time IS NULL
                 OR t.end_precision <> 0
                 OR t.end_time IS NOT NULL)
      )
  );
