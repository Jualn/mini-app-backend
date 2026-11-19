-- Identity / Organization Phase A. Expand only; no User/Points data backfill or grants.
-- UTC microseconds; missing person head is UNKNOWN with revision zero.
CREATE TABLE person_authority_epoch (
 id TINYINT PRIMARY KEY, epoch BIGINT NOT NULL,
 CONSTRAINT ck_person_epoch CHECK(id=1 AND epoch>=0)
) ENGINE=InnoDB;
INSERT INTO person_authority_epoch VALUES(1,0);

CREATE TABLE person_foundation_event (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY,
 aggregate_type VARCHAR(16) NOT NULL, aggregate_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 aggregate_version BIGINT NOT NULL, operation VARCHAR(64) NOT NULL,
 before_json JSON NULL, after_json JSON NOT NULL,
 operator_user_id BIGINT UNSIGNED NOT NULL, recorded_at DATETIME(6) NOT NULL,
 reason VARCHAR(2000) NOT NULL, verification_reference VARCHAR(256) NOT NULL,
 source VARCHAR(32) NOT NULL, corrects_event_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 receipt_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL, trace_id VARCHAR(64) NULL,
 UNIQUE KEY uk_person_event_version(aggregate_type,aggregate_id,aggregate_version),
 KEY idx_person_event_history(aggregate_type,aggregate_id,id),
 CONSTRAINT fk_person_event_actor FOREIGN KEY(operator_user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_person_event_correct FOREIGN KEY(corrects_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_person_event_type CHECK(aggregate_type IN ('PERSON','ORGANIZATION','SCOPE','BOOTSTRAP')),
 CONSTRAINT ck_person_event_source CHECK(source IN ('LOCAL_MANUAL','LOCAL_MAINTENANCE','APPROVED_BOOTSTRAP')),
 CONSTRAINT ck_person_event_revision CHECK(aggregate_version>0),
 CONSTRAINT ck_person_event_reason CHECK(CHAR_LENGTH(reason)>0 AND CHAR_LENGTH(verification_reference)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE person_command_receipt (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY,
 actor BIGINT UNSIGNED NOT NULL, operation VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
 target VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 command_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_json JSON NOT NULL, etag VARCHAR(128) NOT NULL, location VARCHAR(256) NULL,
 status SMALLINT NOT NULL, event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_person_command(actor,operation,target,command_key),
 CONSTRAINT fk_person_receipt_actor FOREIGN KEY(actor) REFERENCES user_profile(id),
 CONSTRAINT fk_person_receipt_event FOREIGN KEY(event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_person_receipt_status CHECK(status IN (200,201)),
 CONSTRAINT ck_person_receipt_key CHECK(CHAR_LENGTH(command_key)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE person_fact_current (
 user_id BIGINT UNSIGNED PRIMARY KEY, revision BIGINT NOT NULL,
 identity_verification VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 identity_presence VARCHAR(16) NULL, current_record_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 stage_id VARCHAR(64) NULL, identity_status VARCHAR(16) NULL,
 identity_valid_from DATETIME(6) NULL, identity_valid_until DATETIME(6) NULL,
 identity_verified_by BIGINT UNSIGNED NULL, identity_verified_at DATETIME(6) NULL,
 membership_verification VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
 membership_verified_by BIGINT UNSIGNED NULL, membership_verified_at DATETIME(6) NULL,
 last_change_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 identity_change_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 membership_change_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 CONSTRAINT fk_person_head_user FOREIGN KEY(user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_person_head_event FOREIGN KEY(last_change_id) REFERENCES person_foundation_event(id),
 CONSTRAINT fk_person_head_identity_event FOREIGN KEY(identity_change_id) REFERENCES person_foundation_event(id),
 CONSTRAINT fk_person_head_membership_event FOREIGN KEY(membership_change_id) REFERENCES person_foundation_event(id),
 CONSTRAINT fk_person_head_record FOREIGN KEY(current_record_id) REFERENCES person_foundation_event(id),
 CONSTRAINT fk_person_identity_verifier FOREIGN KEY(identity_verified_by) REFERENCES user_profile(id),
 CONSTRAINT fk_person_membership_verifier FOREIGN KEY(membership_verified_by) REFERENCES user_profile(id),
 CONSTRAINT ck_person_head_revision CHECK(revision>0),
 CONSTRAINT ck_person_identity_verification CHECK(identity_verification IN ('UNKNOWN','VERIFIED','DISPUTED')),
 CONSTRAINT ck_person_membership_verification CHECK(membership_verification IN ('UNKNOWN','VERIFIED','DISPUTED')),
 CONSTRAINT ck_person_identity_shape CHECK(
 (identity_verification<>'VERIFIED' AND identity_presence IS NULL AND current_record_id IS NULL AND stage_id IS NULL AND identity_status IS NULL AND identity_valid_from IS NULL AND identity_valid_until IS NULL AND identity_verified_by IS NULL AND identity_verified_at IS NULL)
 OR (identity_verification='VERIFIED' AND identity_verified_by IS NOT NULL AND identity_verified_at IS NOT NULL AND
 ((identity_presence='NONE' AND current_record_id IS NULL AND stage_id IS NULL AND identity_status IS NULL AND identity_valid_from IS NULL AND identity_valid_until IS NULL)
 OR (identity_presence='RECORDED' AND current_record_id IS NOT NULL AND stage_id IS NOT NULL AND identity_status IN ('ACTIVE','INACTIVE','REVOKED') AND identity_valid_from IS NOT NULL)))),
 CONSTRAINT ck_person_identity_period CHECK(identity_valid_until IS NULL OR identity_valid_until>identity_valid_from),
 CONSTRAINT ck_person_membership_shape CHECK(
 (membership_verification='VERIFIED' AND membership_verified_by IS NOT NULL AND membership_verified_at IS NOT NULL)
 OR (membership_verification<>'VERIFIED' AND membership_verified_by IS NULL AND membership_verified_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE person_organization (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY, name VARCHAR(120) NOT NULL,
 description VARCHAR(2000) NOT NULL, status VARCHAR(16) NOT NULL,
 revision BIGINT NOT NULL, created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 last_change_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 KEY idx_person_org_status(status,id),
 CONSTRAINT fk_person_org_event FOREIGN KEY(last_change_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_person_org_state CHECK(status IN ('ACTIVE','INACTIVE') AND revision>0 AND CHAR_LENGTH(name)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE person_membership (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL,
 organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 valid_from DATETIME(6) NOT NULL, valid_until DATETIME(6) NULL,
 created_event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 ended_event_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN ended_event_id IS NULL THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_person_member_slot(user_id,organization_id,active_slot),
 KEY idx_person_member_org(organization_id,ended_event_id,user_id),
 CONSTRAINT fk_person_member_user FOREIGN KEY(user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_person_member_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT fk_person_member_created FOREIGN KEY(created_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT fk_person_member_ended FOREIGN KEY(ended_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_person_member_period CHECK(valid_until IS NULL OR valid_until>valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE person_scope_grant (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY, grantee_user_id BIGINT UNSIGNED NOT NULL,
 mode VARCHAR(16) NOT NULL, domain VARCHAR(16) NOT NULL, actions VARCHAR(64) COLLATE utf8mb4_0900_bin NOT NULL,
 target_type VARCHAR(16) NOT NULL, target_id VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL,
 valid_from DATETIME(6) NOT NULL, valid_until DATETIME(6) NULL,
 revoked_at DATETIME(6) NULL, revoked_by BIGINT UNSIGNED NULL, revoke_reason VARCHAR(2000) NULL,
 source VARCHAR(32) NOT NULL, authority_grant_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 granted_by BIGINT UNSIGNED NOT NULL, reason VARCHAR(2000) NOT NULL, verification_reference VARCHAR(256) NOT NULL,
 revision BIGINT NOT NULL, last_event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL, current_slot TINYINT NULL DEFAULT 1,
 UNIQUE KEY uk_person_scope_slot(grantee_user_id,mode,domain,target_type,target_id,actions,current_slot),
 KEY idx_person_scope_authority(authority_grant_id),
 KEY idx_person_scope_actor(grantee_user_id,mode,domain,revoked_at),
 CONSTRAINT fk_person_scope_grantee FOREIGN KEY(grantee_user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_person_scope_actor FOREIGN KEY(granted_by) REFERENCES user_profile(id),
 CONSTRAINT fk_person_scope_revoker FOREIGN KEY(revoked_by) REFERENCES user_profile(id),
 CONSTRAINT fk_person_scope_authority FOREIGN KEY(authority_grant_id) REFERENCES person_scope_grant(id),
 CONSTRAINT fk_person_scope_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_person_scope_mode CHECK(mode IN ('OPERATE','ADMINISTER')),
 CONSTRAINT ck_person_scope_domain CHECK(domain IN ('IDENTITY','ORGANIZATION','POINTS')),
 CONSTRAINT ck_person_scope_actions CHECK(
 (domain IN ('IDENTITY','ORGANIZATION') AND actions IN ('READ','MAINTAIN','MAINTAIN,READ'))
 OR (domain='POINTS' AND actions IN ('READ','REVIEW','REVERSE','READ,REVIEW','READ,REVERSE','REVERSE,REVIEW','READ,REVERSE,REVIEW'))),
 CONSTRAINT ck_person_scope_target CHECK(target_type IN ('PERSON','ORGANIZATION') OR (target_type='DIRECTORY' AND target_id='' AND mode='ADMINISTER' AND domain='ORGANIZATION')),
 CONSTRAINT ck_person_scope_source CHECK(
 (mode='ADMINISTER' AND source='APPROVED_BOOTSTRAP' AND authority_grant_id IS NULL)
 OR (mode='OPERATE' AND source='LOCAL_MAINTENANCE' AND authority_grant_id IS NOT NULL)),
 CONSTRAINT ck_person_scope_period CHECK(valid_until IS NULL OR valid_until>valid_from),
 CONSTRAINT ck_person_scope_revision CHECK(revision>0 AND (current_slot IS NULL OR current_slot=1)),
 CONSTRAINT ck_person_scope_revoke CHECK(
 (revoked_at IS NULL AND revoked_by IS NULL AND revoke_reason IS NULL)
 OR (revoked_at>=valid_from AND revoked_by IS NOT NULL AND revoke_reason IS NOT NULL AND current_slot IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Controlled catalog additive registration, preserving every existing lifecycle/readiness/grantable.
-- No real Assignment is created or changed; all new definitions remain NOT_READY.
INSERT INTO rbac_permission_definition(code,description,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('identity:read','受控读取本地业务身份及历史','["IDENTITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c');
INSERT INTO rbac_permission_definition(code,description,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('organization:read','受控读取平面目录、成员核实事实及历史','["ORGANIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c');
INSERT INTO rbac_permission_definition(code,description,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('person-scope:read','读取明确ADMINISTER控制范围内的人员管理授权','["ORGANIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c');
INSERT INTO rbac_permission_definition(code,description,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('person-scope:manage','在独立ADMINISTER事实允许范围内授予/撤销OPERATE目标授权','["ORGANIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c');
INSERT INTO rbac_role(code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('PERSON_FACT_READER_V1','人员事实读取员','["IDENTITY", "ORGANIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('PERSON_FACT_READER_V1','identity:read');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('PERSON_FACT_READER_V1','organization:read');
INSERT INTO rbac_role(code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('PERSON_FACT_STEWARD_V1','本地人员事实维护员','["IDENTITY", "ORGANIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('PERSON_FACT_STEWARD_V1','identity:read');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('PERSON_FACT_STEWARD_V1','identity:map-manage');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('PERSON_FACT_STEWARD_V1','organization:read');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('PERSON_FACT_STEWARD_V1','organization:map-manage');
INSERT INTO rbac_role(code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('PERSON_SCOPE_ADMIN_V1','人员范围治理员','["ORGANIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('PERSON_SCOPE_ADMIN_V1','person-scope:read');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('PERSON_SCOPE_ADMIN_V1','person-scope:manage');
UPDATE rbac_permission_definition SET catalog_digest='cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c';
UPDATE rbac_role SET catalog_digest='cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c';
