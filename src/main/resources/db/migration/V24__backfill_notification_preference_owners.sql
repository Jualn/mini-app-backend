-- Backfill historical users into the canonical notification-preference owner.
-- Run while legacy setting writes are quiesced so the captured values and owner switch
-- form one release boundary. Existing canonical owners are intentionally left untouched.

CREATE TEMPORARY TABLE tmp_notification_preference_backfill_guard (
  no_unowned_preference_conflicts_or_invalid_legacy_values TINYINT NOT NULL
) ENGINE=InnoDB;

-- Abort instead of guessing when a previous partial/manual migration left preferences
-- without the corresponding owner, or when legacy booleans contain invalid values.
INSERT INTO tmp_notification_preference_backfill_guard (
  no_unowned_preference_conflicts_or_invalid_legacy_values
)
SELECT CASE
  WHEN EXISTS (
    SELECT 1
    FROM user_profile up
    LEFT JOIN notification_preference_owner npo ON npo.user_id = up.id
    WHERE npo.user_id IS NULL
      AND EXISTS (
        SELECT 1
        FROM notification_preference np
        WHERE np.user_id = up.id
      )
  ) OR EXISTS (
    SELECT 1
    FROM user_setting us
    WHERE us.notify_activity_remind NOT IN (0, 1)
       OR us.notify_exam_remind NOT IN (0, 1)
  )
  THEN NULL
  ELSE 1
END;

CREATE TEMPORARY TABLE tmp_notification_preference_backfill (
  user_id BIGINT UNSIGNED NOT NULL,
  activity_enabled TINYINT(1) NOT NULL,
  public_event_enabled TINYINT(1) NOT NULL,
  PRIMARY KEY (user_id)
) ENGINE=InnoDB;

START TRANSACTION;

-- A missing user_setting row has the same effective legacy value as its DDL default: false.
-- Lock the selected source rows until both preferences and the owner switch are committed.
INSERT INTO tmp_notification_preference_backfill (
  user_id,
  activity_enabled,
  public_event_enabled
)
SELECT
  up.id,
  COALESCE(us.notify_activity_remind, 0),
  COALESCE(us.notify_exam_remind, 0)
FROM user_profile up
LEFT JOIN user_setting us ON us.user_id = up.id
LEFT JOIN notification_preference_owner npo ON npo.user_id = up.id
WHERE npo.user_id IS NULL
FOR UPDATE;

INSERT INTO notification_preference (
  user_id,
  category,
  channel,
  enabled,
  source
)
SELECT
  user_id,
  'ACTIVITY',
  'WECHAT_OFFICIAL_ACCOUNT',
  activity_enabled,
  'LEGACY_MIGRATION'
FROM tmp_notification_preference_backfill;

INSERT INTO notification_preference (
  user_id,
  category,
  channel,
  enabled,
  source
)
SELECT
  user_id,
  'PUBLIC_EVENT',
  'WECHAT_OFFICIAL_ACCOUNT',
  public_event_enabled,
  'LEGACY_MIGRATION'
FROM tmp_notification_preference_backfill;

INSERT INTO notification_preference_owner (
  user_id,
  owner,
  migrated_at
)
SELECT
  user_id,
  'CANONICAL',
  CURRENT_TIMESTAMP(3)
FROM tmp_notification_preference_backfill;

COMMIT;

DROP TEMPORARY TABLE tmp_notification_preference_backfill;
DROP TEMPORARY TABLE tmp_notification_preference_backfill_guard;
