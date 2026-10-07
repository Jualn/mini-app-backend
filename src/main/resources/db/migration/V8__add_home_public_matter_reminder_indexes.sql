-- Homepage countdowns reuse public-matter timeline nodes. The event start is a
-- synthetic fallback only when no explicit future node exists for that matter.
ALTER TABLE timeline
    ADD INDEX idx_timeline_home_reminder (target_type, start_time, target_id, id);

ALTER TABLE exam_subscription
    ADD INDEX idx_exam_subscription_user_active
        (user_id, status, notify_enable, exam_info_id);
