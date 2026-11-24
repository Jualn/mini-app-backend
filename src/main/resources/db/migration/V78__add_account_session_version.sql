-- User-owned credential generation; no session, role or Person backfill.
ALTER TABLE user_profile
 ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0 COMMENT 'Monotonic account credential generation; ban invalidates both login types atomically',
 ADD CONSTRAINT ck_user_session_version CHECK (session_version >= 0);
