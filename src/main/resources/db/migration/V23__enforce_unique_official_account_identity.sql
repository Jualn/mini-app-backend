-- One Official Account identity may belong to at most one active or historical user row.
-- Existing duplicates intentionally block this migration; run the matching read-only preflight first.
ALTER TABLE `user_profile`
  ADD UNIQUE KEY `uk_user_profile_mp_openid` (`mp_openid`);
