-- Read-only rollout evidence. Run on the verified V28-or-newer target before enabling writers.
-- Never infer that a PENDING log identifies the value currently in user_profile.
SELECT target_type, final_result, COUNT(*) AS audit_count
FROM content_audit_log WHERE target_type IN (5, 6, 7, 8)
GROUP BY target_type, final_result;

SELECT audit.id, audit.target_type, audit.target_id, audit.final_result,
       (audit.wx_trace_id IS NOT NULL) AS has_provider_trace,
       job.id AS job_id, job.job_type, job.status AS job_status
FROM content_audit_log audit
LEFT JOIN async_job job ON job.subject_type = 'content-audit-log'
    AND job.subject_id = CAST(audit.id AS CHAR)
WHERE audit.target_type IN (5, 6, 7, 8) AND audit.final_result = 0
ORDER BY audit.id;

SELECT COUNT(*) AS invalid_profile_nickname_count
FROM user_profile WHERE deleted_at IS NULL AND nickname IS NULL;

-- After V29, verify expansion/backfill defaults and classify mutable legacy image references.
SELECT COUNT(*) AS missing_revision_count FROM user_profile WHERE profile_revision IS NULL;
SELECT COUNT(*) AS legacy_avatar_count FROM user_profile
WHERE deleted_at IS NULL AND avatar_url IS NOT NULL AND avatar_snapshot_key IS NULL;
SELECT COUNT(*) AS legacy_background_count FROM user_profile
WHERE deleted_at IS NULL AND background_url IS NOT NULL AND background_snapshot_key IS NULL;
