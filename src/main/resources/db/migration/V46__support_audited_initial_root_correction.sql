-- Forward-only correction of an explicitly mistaken initial ROOT. No personnel writes.
ALTER TABLE rbac_offline_operation
 DROP CHECK ck_rbac_offline_kind,
 DROP CHECK ck_rbac_offline_version,
 DROP CHECK ck_rbac_offline_approval,
 ADD CONSTRAINT ck_rbac_offline_kind CHECK (kind IN
   ('BOOTSTRAP','SOURCE_SWITCH','RECOVERY','CORRECT_INITIAL_ROOT','INITIAL_ROLE_GRANT')),
 ADD CONSTRAINT ck_rbac_offline_version CHECK (
   (snapshot_version=1 AND kind IN ('BOOTSTRAP','SOURCE_SWITCH','RECOVERY')) OR
   (snapshot_version=2 AND kind='BOOTSTRAP') OR
   (snapshot_version=3 AND kind IN ('CORRECT_INITIAL_ROOT','INITIAL_ROLE_GRANT'))),
 ADD CONSTRAINT ck_rbac_offline_approval CHECK (
   CHAR_LENGTH(approval_ref)>0 AND JSON_TYPE(approvals_json)='ARRAY' AND
   ((snapshot_version=1 AND JSON_LENGTH(approvals_json)=2) OR
    (snapshot_version IN (2,3) AND JSON_LENGTH(approvals_json)=1)));
