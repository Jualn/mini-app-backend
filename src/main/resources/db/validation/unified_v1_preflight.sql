-- READ ONLY. Run only after V62 on an explicitly selected target; do not infer/backfill history.
SELECT 'legacy_pending_application' AS finding,COUNT(*) AS rows_found FROM point_application WHERE status='PENDING' AND accepting_organization_id IS NULL;
SELECT 'legacy_ledger_attribution' AS finding,COUNT(*) AS rows_found FROM point_ledger WHERE accepting_organization_id IS NULL;
SELECT 'legacy_task_ownership' AS finding,COUNT(*) AS rows_found FROM learning_task WHERE management_target IS NULL;
SELECT 'unified_assignment_without_scope' AS finding,COUNT(*) AS rows_found FROM rbac_user_role_assignment a LEFT JOIN person_business_scope s ON s.assignment_id=a.id WHERE a.origin_kind='UNIFIED' AND s.id IS NULL;
SELECT 'bound_application_cycle_mismatch' AS finding,COUNT(*) AS rows_found FROM point_application a JOIN person_membership m ON m.id=a.membership_id WHERE a.applicant_user_id<>m.user_id OR a.accepting_organization_id<>m.organization_id;
SELECT 'bound_ledger_cycle_mismatch' AS finding,COUNT(*) AS rows_found FROM point_ledger l JOIN person_membership m ON m.id=l.membership_id WHERE l.user_id<>m.user_id OR l.accepting_organization_id<>m.organization_id;
SELECT 'authority_missing_original_root' AS finding,COUNT(*) AS rows_found FROM rbac_delegation_authority d JOIN rbac_user_role_assignment a ON a.id=d.root_assignment_id WHERE a.role_code<>'ROOT' OR a.namespace<>'AUTHORIZATION' OR a.user_id<>d.granted_by OR a.origin_kind<>'STANDALONE';

-- Candidate impact only, not an authorization proof. No grants, data repair, or legacy fallback.
-- Count legacy whole-person access which could previously need two independent organization rows.
SELECT 'legacy_complete_person_multi_source_candidate' AS finding,COUNT(*) AS rows_found FROM (
 SELECT s.grantee_user_id,s.domain,m.user_id
 FROM person_scope_grant s JOIN person_membership m ON m.organization_id=s.target_id
 JOIN person_fact_current h ON h.user_id=m.user_id
 WHERE s.mode='OPERATE' AND s.target_type='ORGANIZATION' AND FIND_IN_SET('READ',s.actions)>0
 AND s.revoked_at IS NULL AND s.valid_from<=UTC_TIMESTAMP(6) AND (s.valid_until IS NULL OR s.valid_until>UTC_TIMESTAMP(6))
 AND h.membership_verification='VERIFIED' AND m.ended_event_id IS NULL AND m.valid_from<=UTC_TIMESTAMP(6) AND (m.valid_until IS NULL OR m.valid_until>UTC_TIMESTAMP(6))
 GROUP BY s.grantee_user_id,s.domain,m.user_id HAVING COUNT(DISTINCT s.target_id)>1
) candidates;
SELECT 'unified_complete_person_multi_source_candidate' AS finding,COUNT(*) AS rows_found FROM (
 SELECT s.grantee_user_id,s.duty_code,m.user_id
 FROM person_business_scope s JOIN person_membership m ON m.organization_id=JSON_UNQUOTE(JSON_EXTRACT(s.selection,'$.target.organizationId'))
 WHERE s.duty_code IN ('PERSON_IDENTITY_MAINTAIN','PERSON_IDENTITY_READ','PERSON_ORGANIZATION_READ','POINTS_PERSON_LEDGER')
 AND JSON_UNQUOTE(JSON_EXTRACT(s.selection,'$.target.type'))='ORGANIZATION'
 AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(6)
 AND m.ended_event_id IS NULL AND m.valid_from<=UTC_TIMESTAMP(6) AND (m.valid_until IS NULL OR m.valid_until>UTC_TIMESTAMP(6))
 GROUP BY s.grantee_user_id,s.duty_code,m.user_id HAVING COUNT(DISTINCT m.organization_id)>1
) candidates;
SELECT 'new_narrow_role_standalone_source' AS finding,COUNT(*) AS rows_found FROM rbac_user_role_assignment
 WHERE role_code IN ('ARTICLE_CATEGORY_STEWARD_V1','POST_DICTIONARY_STEWARD_V1','POST_MODERATOR_V1') AND origin_kind<>'UNIFIED';
SELECT 'catalog_binary_digest_mismatch' AS finding,COUNT(*) AS rows_found FROM (
 SELECT catalog_digest FROM rbac_role UNION ALL SELECT catalog_digest FROM rbac_permission_definition
) definitions WHERE catalog_digest<>'ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424';
