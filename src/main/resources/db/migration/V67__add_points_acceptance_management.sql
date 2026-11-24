-- Register the separately approved acceptance-management duty. No Assignment or organization admission is seeded.
-- Stop mixed catalog binaries before migration; publish only this implemented capability.
INSERT INTO rbac_permission_definition(code,description,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest)
VALUES('points:acceptance-manage','管理组织积分受理资格','["POINTS"]','ENABLED','READY',TRUE,NULL,'2a08a320843a0f536c7449df025d1751781374cacbbc84654668b80a42466aae');
INSERT INTO rbac_role(code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest)
VALUES('POINTS_ACCEPTANCE_STEWARD_V1','积分受理组织管理员','["POINTS"]','ENABLED','READY',TRUE,NULL,'2a08a320843a0f536c7449df025d1751781374cacbbc84654668b80a42466aae');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('POINTS_ACCEPTANCE_STEWARD_V1','points:acceptance-manage');

CREATE TABLE point_acceptance_command (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY COMMENT 'Immutable acceptance decision command identity',
 actor BIGINT UNSIGNED NOT NULL COMMENT 'Actual operating User, not Person',
 organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Exact organization whose acceptance is governed',
 command_key VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Caller intent key, scoped by actor and organization',
 expected_version VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Original strong If-Match value without quotes',
 enabled BOOLEAN NOT NULL COMMENT 'Explicit acceptance decision; false is known disabled',
 reason VARCHAR(2000) NOT NULL COMMENT 'Operator justification retained with immutable decision',
 result_json JSON NOT NULL COMMENT 'Original success representation for same-key recovery',
 evidence_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Immutable evidence published by this command',
 recorded_at DATETIME(6) NOT NULL COMMENT 'UTC decision time from database clock',
 UNIQUE KEY uk_acceptance_intent(actor,organization_id,command_key),
 UNIQUE KEY uk_acceptance_evidence(evidence_id),
 KEY idx_acceptance_org_history(organization_id,recorded_at,id),
 CONSTRAINT fk_acceptance_command_actor FOREIGN KEY(actor) REFERENCES user_profile(id),
 CONSTRAINT fk_acceptance_command_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT fk_acceptance_command_evidence FOREIGN KEY(evidence_id) REFERENCES point_acceptance_evidence(id),
 CONSTRAINT ck_acceptance_command_reason CHECK(CHAR_LENGTH(TRIM(reason))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Permanent audited organization acceptance decisions and idempotency receipts';
CREATE INDEX idx_point_acceptance_history ON point_acceptance_evidence(organization_id,recorded_at,id);
