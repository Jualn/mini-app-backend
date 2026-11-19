-- Invalidation is a durable fact even if a parent is later republished.
ALTER TABLE content_safety_review ADD COLUMN invalidation_reason VARCHAR(32) NULL;
