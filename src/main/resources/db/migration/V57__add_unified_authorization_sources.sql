-- Add provenance to the existing RBAC Assignment; no new role or permission facts.
ALTER TABLE rbac_user_role_assignment
 ADD COLUMN origin_kind VARCHAR(16) NOT NULL DEFAULT 'STANDALONE',
 ADD CONSTRAINT ck_rbac_assignment_origin CHECK(origin_kind IN ('STANDALONE','UNIFIED')
  AND (origin_kind<>'UNIFIED' OR (current_slot IS NULL AND role_code<>'ROOT')));

CREATE TABLE rbac_delegation_authority (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY,
 grantee_user_id BIGINT UNSIGNED NOT NULL, granted_by BIGINT UNSIGNED NOT NULL,
 root_assignment_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 ceilings JSON NOT NULL, member_bindings JSON NOT NULL,
 recipient_rule VARCHAR(32) NOT NULL, granted_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 reason VARCHAR(2000) NOT NULL, revision BIGINT NOT NULL DEFAULT 1,
 revoked_at DATETIME(6) NULL, revoked_by BIGINT UNSIGNED NULL, revocation_reason VARCHAR(2000) NULL,
 change_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 KEY idx_rbac_delegation_holder(grantee_user_id,id), KEY idx_rbac_delegation_root(root_assignment_id),
 CONSTRAINT fk_rbac_delegation_holder FOREIGN KEY(grantee_user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_rbac_delegation_actor FOREIGN KEY(granted_by) REFERENCES user_profile(id),
 CONSTRAINT fk_rbac_delegation_root FOREIGN KEY(root_assignment_id) REFERENCES rbac_user_role_assignment(id),
 CONSTRAINT fk_rbac_delegation_revoker FOREIGN KEY(revoked_by) REFERENCES user_profile(id),
 CONSTRAINT ck_rbac_delegation_period CHECK(expires_at>granted_at),
 CONSTRAINT ck_rbac_delegation_rule CHECK(recipient_rule IN ('SAME_ORGANIZATION_MEMBERS','INDEPENDENT_APPROVAL')),
 CONSTRAINT ck_rbac_delegation_json CHECK(JSON_TYPE(ceilings)='ARRAY' AND JSON_LENGTH(ceilings)>0 AND JSON_TYPE(member_bindings)='ARRAY'),
 CONSTRAINT ck_rbac_delegation_revoke CHECK((revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL AND revision=1)
  OR (revoked_at>=granted_at AND revoked_by IS NOT NULL AND revocation_reason IS NOT NULL AND revision=2))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Person owns business targets and membership-cycle bindings; RBAC still owns the actual role Assignment.
CREATE TABLE person_business_scope (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY, grantee_user_id BIGINT UNSIGNED NOT NULL,
 duty_code VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
 assignment_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 authority_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 root_assignment_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 selection JSON NOT NULL, member_bindings JSON NOT NULL,
 granted_by BIGINT UNSIGNED NOT NULL, granted_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 change_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 revoked_at DATETIME(6) NULL, revoked_by BIGINT UNSIGNED NULL, revocation_reason VARCHAR(2000) NULL,
 UNIQUE KEY uk_person_business_assignment(assignment_id), KEY idx_person_business_holder(grantee_user_id,id),
 KEY idx_person_business_authority(authority_id,id),
 CONSTRAINT fk_person_business_holder FOREIGN KEY(grantee_user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_person_business_assignment FOREIGN KEY(assignment_id) REFERENCES rbac_user_role_assignment(id),
 CONSTRAINT fk_person_business_authority FOREIGN KEY(authority_id) REFERENCES rbac_delegation_authority(id),
 CONSTRAINT fk_person_business_root FOREIGN KEY(root_assignment_id) REFERENCES rbac_user_role_assignment(id),
 CONSTRAINT fk_person_business_actor FOREIGN KEY(granted_by) REFERENCES user_profile(id),
 CONSTRAINT fk_person_business_revoker FOREIGN KEY(revoked_by) REFERENCES user_profile(id),
 CONSTRAINT ck_person_business_source CHECK((authority_id IS NULL AND root_assignment_id IS NOT NULL) OR (authority_id IS NOT NULL AND root_assignment_id IS NULL)),
 CONSTRAINT ck_person_business_period CHECK(expires_at>granted_at),
 CONSTRAINT ck_person_business_json CHECK(JSON_TYPE(selection)='OBJECT' AND JSON_TYPE(member_bindings)='ARRAY'),
 CONSTRAINT ck_person_business_revoke CHECK((revoked_at IS NULL AND revoked_by IS NULL AND revocation_reason IS NULL)
  OR (revoked_at>=granted_at AND revoked_by IS NOT NULL AND revocation_reason IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE rbac_unified_command (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY, actor BIGINT UNSIGNED NOT NULL,
 operation VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL, target VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_json JSON NOT NULL, result_json JSON NOT NULL, audit_json JSON NOT NULL, occurred_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_rbac_unified_command(actor,operation,target,command_key),
 CONSTRAINT fk_rbac_unified_actor FOREIGN KEY(actor) REFERENCES user_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE rbac_duty_preview (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY, actor BIGINT UNSIGNED NOT NULL,
 intent_json JSON NOT NULL, snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_rbac_preview_actor FOREIGN KEY(actor) REFERENCES user_profile(id),
 CONSTRAINT ck_rbac_preview_period CHECK(expires_at>created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE rbac_duty_cursor (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY, actor BIGINT UNSIGNED NOT NULL,
 binding CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, item_offset BIGINT NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_rbac_duty_cursor_actor FOREIGN KEY(actor) REFERENCES user_profile(id),
 CONSTRAINT ck_rbac_duty_cursor_offset CHECK(item_offset>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
