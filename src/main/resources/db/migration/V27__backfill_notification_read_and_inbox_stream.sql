-- Compatibility confirmation time, NOT a claim about historical click time.
SET @notification_read_confirmation_at = CURRENT_TIMESTAMP(6);
UPDATE notification SET read_at = @notification_read_confirmation_at WHERE is_read = 1;

-- Historical ordering is a migration baseline only. New entry order is serialized by user counter.
CREATE TEMPORARY TABLE notification_inbox_backfill (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    inbox_seq BIGINT NOT NULL
);
INSERT INTO notification_inbox_backfill (id, user_id, inbox_seq)
SELECT n.id, n.user_id, ROW_NUMBER() OVER (PARTITION BY n.user_id ORDER BY n.created_at, n.id)
FROM notification n
WHERE n.inbox_generation = 'LEGACY'
   OR EXISTS (SELECT 1 FROM notification_delivery d
              WHERE d.notification_id = n.id AND d.channel = 'IN_APP' AND d.status = 'DELIVERED');
UPDATE notification n JOIN notification_inbox_backfill b ON b.id = n.id SET n.inbox_seq = b.inbox_seq;
INSERT INTO notification_inbox_counter (user_id, head_seq)
SELECT user_id, MAX(inbox_seq) FROM notification_inbox_backfill GROUP BY user_id;
DROP TEMPORARY TABLE notification_inbox_backfill;
