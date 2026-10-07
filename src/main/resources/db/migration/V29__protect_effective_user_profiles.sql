-- Expand before switching every profile writer and callback to the new application.
-- Historical values remain unchanged; pending legacy moderation needs a rollout preflight.
ALTER TABLE user_profile
    ADD COLUMN profile_revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN avatar_snapshot_key VARCHAR(512) NULL,
    ADD COLUMN background_snapshot_key VARCHAR(512) NULL;
