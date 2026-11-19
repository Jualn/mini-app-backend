-- MANUAL WRITE, never a Flyway migration or automatic application startup action.
-- Only after explicit target approval, V41, backup and quiescing old User writers.
-- This task executes it only in its owned synthetic environment.
-- Preserve the complete client output (target, batch, counts) in deployment evidence.
SELECT DATABASE() AS target_database, UTC_TIMESTAMP(6) AS started_at;
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;
SET @rbac_authority_batch=UUID();
SELECT COUNT(*) AS users_before, COALESCE(SUM(a.user_id IS NULL),0) AS missing_before,
       COALESCE(SUM(a.source='ASSIGNMENT'),0) AS assignments_preserved
FROM user_profile u LEFT JOIN rbac_authorization_authority a ON a.user_id=u.id;
INSERT INTO rbac_authorization_authority(user_id,source,version,updated_at)
SELECT u.id,'LEGACY',@rbac_authority_batch,UTC_TIMESTAMP(6) FROM user_profile u ORDER BY u.id
ON DUPLICATE KEY UPDATE user_id=rbac_authorization_authority.user_id;
SELECT @rbac_authority_batch AS batch_id,COUNT(*) AS initialized
FROM rbac_authorization_authority WHERE source='LEGACY' AND version=@rbac_authority_batch;
SELECT COUNT(*) AS users_after, COALESCE(SUM(a.user_id IS NULL),0) AS missing_after,
       COALESCE(SUM(a.source='ASSIGNMENT'),0) AS assignments_preserved,
       COALESCE(SUM(a.user_id IS NOT NULL AND
         (a.source NOT IN ('LEGACY','ASSIGNMENT') OR a.version NOT REGEXP '[^[:space:]]')),0) AS conflicts_after
FROM user_profile u LEFT JOIN rbac_authorization_authority a ON a.user_id=u.id;
COMMIT;
-- Never repair conflicts by overwriting source/version. Stop deployment if either
-- missing_after or conflicts_after is nonzero. Repeat the read-only preflight
-- after this commit, before starting the gated binary. Idempotent repeats insert zero.
