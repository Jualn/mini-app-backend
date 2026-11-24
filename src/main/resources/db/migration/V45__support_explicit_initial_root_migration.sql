-- Preserve v1 dual-signature synthetic operations. v2 is an explicitly approved
-- operator manifest for one initial ROOT, not a normal authenticated HTTP grant.
-- No catalog activation, personnel selection or source switching in this DDL.
ALTER TABLE rbac_offline_operation
 DROP CHECK ck_rbac_offline_version,
 DROP CHECK ck_rbac_offline_approval,
 ADD CONSTRAINT ck_rbac_offline_version CHECK (
   snapshot_version=1 OR (snapshot_version=2 AND kind='BOOTSTRAP')),
 ADD CONSTRAINT ck_rbac_offline_approval CHECK (
   CHAR_LENGTH(approval_ref)>0 AND JSON_TYPE(approvals_json)='ARRAY' AND
   ((snapshot_version=1 AND JSON_LENGTH(approvals_json)=2) OR
    (snapshot_version=2 AND JSON_LENGTH(approvals_json)=1)));
