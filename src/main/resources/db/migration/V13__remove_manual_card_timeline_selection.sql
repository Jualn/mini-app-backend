-- cardTimeline is now derived from timeline on every read; manual selection is no longer persisted.
ALTER TABLE activity
    DROP COLUMN card_timeline_node_key;

ALTER TABLE exam_info
    DROP COLUMN card_timeline_node_key;
