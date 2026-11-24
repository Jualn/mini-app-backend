-- READ ONLY, after V43. Not an execution authorization or a complete disaster-recovery proof.
SELECT DATABASE() AS target_database, UTC_TIMESTAMP(6) AS evaluated_at;
SELECT kind,COUNT(*) AS committed_operations FROM rbac_offline_operation GROUP BY kind;
SELECT o.id,o.kind,COUNT(e.id) AS effects,JSON_LENGTH(o.receipt,'$.effects') AS receipt_effects
FROM rbac_offline_operation o LEFT JOIN rbac_offline_effect e ON e.operation_id=o.id
GROUP BY o.id,o.kind,o.snapshot_version,o.request_json,o.receipt
HAVING COUNT(e.id)<>IF(o.snapshot_version=2,1+JSON_LENGTH(o.request_json,'$.ordinaryUsers'),IF(o.kind IN ('SOURCE_SWITCH','INITIAL_ROLE_GRANT'),1,2))
    OR JSON_LENGTH(o.receipt,'$.effects')<>COUNT(e.id);
-- Every grant reference must resolve to its real normal GRANT or offline ROOT effect.
SELECT a.id AS assignment_id,a.grant_change_id
FROM rbac_user_role_assignment a
LEFT JOIN rbac_authorization_change c ON c.id=a.grant_change_id AND c.assignment_id=a.id AND c.action='GRANT'
LEFT JOIN rbac_offline_effect e ON e.id=a.grant_change_id AND e.assignment_id=a.id AND e.user_id=a.user_id
LEFT JOIN rbac_offline_operation o ON o.id=e.operation_id AND o.kind IN ('BOOTSTRAP','RECOVERY','CORRECT_INITIAL_ROOT','INITIAL_ROLE_GRANT')
WHERE c.id IS NULL AND o.id IS NULL;
SELECT a.id AS assignment_id,a.revoke_change_id
FROM rbac_user_role_assignment a
LEFT JOIN rbac_authorization_change c ON c.id=a.revoke_change_id AND c.assignment_id=a.id AND c.action='REVOKE'
LEFT JOIN rbac_offline_effect e ON e.id=a.revoke_change_id AND e.user_id=a.user_id AND e.assignment_id IS NULL
LEFT JOIN rbac_offline_operation o ON o.id=e.operation_id AND o.snapshot_version=3 AND o.kind='CORRECT_INITIAL_ROOT'
 AND JSON_CONTAINS(e.before_snapshot,JSON_OBJECT('id',a.id),'$.assignments')=1
 AND JSON_CONTAINS(e.after_snapshot,JSON_OBJECT('id',a.id,'revokeChangeId',e.id),'$.assignments')=1
WHERE a.revoked_at IS NOT NULL AND c.id IS NULL AND o.id IS NULL;
-- Source switch has no new Assignment. ROOT effects must link to AUTHORIZATION/GLOBAL without expiry.
SELECT e.id,e.operation_id,e.assignment_id
FROM rbac_offline_effect e JOIN rbac_offline_operation o ON o.id=e.operation_id
LEFT JOIN rbac_user_role_assignment a ON a.id=e.assignment_id
WHERE (o.kind='SOURCE_SWITCH' AND e.assignment_id IS NOT NULL)
   OR (o.kind IN ('BOOTSTRAP','RECOVERY','CORRECT_INITIAL_ROOT') AND (o.snapshot_version=1 OR e.assignment_id IS NOT NULL) AND
      (a.id IS NULL OR a.grant_change_id<>e.id OR a.user_id<>e.user_id OR a.role_code<>'ROOT'
       OR a.namespace<>'AUTHORIZATION' OR a.scope_type<>'GLOBAL' OR a.expires_at IS NOT NULL));
SELECT e.id,e.operation_id,e.assignment_id
FROM rbac_offline_effect e JOIN rbac_offline_operation o ON o.id=e.operation_id
LEFT JOIN rbac_user_role_assignment a ON a.id=e.assignment_id
WHERE o.kind='INITIAL_ROLE_GRANT' AND (e.assignment_id IS NULL OR a.id IS NULL OR a.user_id<>e.user_id
 OR a.grant_change_id<>e.id OR a.role_code<>'LEARNING_MANAGER' OR a.namespace<>'LEARNING_TASK'
 OR a.scope_type<>'GLOBAL' OR a.expires_at IS NOT NULL);
-- v2 switches every explicitly approved ordinary account without granting it a role.
SELECT e.id,e.operation_id,e.user_id
FROM rbac_offline_effect e JOIN rbac_offline_operation o ON o.id=e.operation_id
WHERE o.snapshot_version=2 AND e.assignment_id IS NULL AND
 (e.user_id=JSON_EXTRACT(o.request_json,'$.rootUserId')
  OR JSON_UNQUOTE(JSON_EXTRACT(e.before_snapshot,'$.subject.source'))<>'LEGACY'
  OR JSON_UNQUOTE(JSON_EXTRACT(e.after_snapshot,'$.subject.source'))<>'ASSIGNMENT'
  OR JSON_LENGTH(e.before_snapshot,'$.assignments')<>0 OR JSON_LENGTH(e.after_snapshot,'$.assignments')<>0);
-- Empty anomaly sets are necessary, not sufficient: match latest off-host revocation/source/key evidence too.
