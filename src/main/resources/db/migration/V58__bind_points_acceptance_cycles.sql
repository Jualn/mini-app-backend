-- No historical acceptance inference, no balance / ledger amount changes.
ALTER TABLE point_application
 ADD COLUMN accepting_organization_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 ADD COLUMN membership_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 ADD COLUMN closed_at DATETIME(6) NULL, ADD COLUMN closed_by_user_id BIGINT UNSIGNED NULL,
 ADD COLUMN closure_reason VARCHAR(2000) NULL,
 ADD CONSTRAINT fk_point_accepting_org FOREIGN KEY(accepting_organization_id) REFERENCES person_organization(id),
 ADD CONSTRAINT fk_point_accepting_member FOREIGN KEY(membership_id) REFERENCES person_membership(id),
 ADD CONSTRAINT fk_point_closed_user FOREIGN KEY(closed_by_user_id) REFERENCES user_profile(id),
 ADD CONSTRAINT ck_point_acceptance_shape CHECK((accepting_organization_id IS NULL AND membership_id IS NULL)
  OR (accepting_organization_id IS NOT NULL AND membership_id IS NOT NULL)),
 ADD KEY idx_point_accepting_state(accepting_organization_id,status,id),
 DROP CHECK chk_point_app_state, DROP CHECK chk_point_app_review,
 ADD CONSTRAINT chk_point_app_state CHECK(status IN ('PENDING','APPROVED','REJECTED','CLOSED')),
 ADD CONSTRAINT chk_point_app_review CHECK(
  (status IN ('PENDING','CLOSED') AND reviewer_user_id IS NULL AND review_note IS NULL AND reviewed_at IS NULL AND awarded_points IS NULL AND grant_ledger_id IS NULL)
  OR (status='REJECTED' AND reviewer_user_id IS NOT NULL AND reviewer_user_id<>applicant_user_id AND review_note IS NOT NULL AND reviewed_at IS NOT NULL AND awarded_points IS NULL AND grant_ledger_id IS NULL)
  OR (status='APPROVED' AND reviewer_user_id IS NOT NULL AND reviewer_user_id<>applicant_user_id AND review_note IS NOT NULL AND reviewed_at IS NOT NULL AND awarded_points IS NOT NULL AND awarded_points>0 AND grant_ledger_id IS NOT NULL)),
 ADD CONSTRAINT ck_point_closed CHECK((status='CLOSED' AND closed_at IS NOT NULL AND closed_by_user_id IS NOT NULL AND closed_by_user_id=applicant_user_id AND closure_reason IS NOT NULL)
  OR (status<>'CLOSED' AND closed_at IS NULL AND closed_by_user_id IS NULL AND closure_reason IS NULL));
ALTER TABLE point_ledger ADD COLUMN accepting_organization_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 ADD COLUMN membership_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 ADD CONSTRAINT fk_point_ledger_accepting FOREIGN KEY(accepting_organization_id) REFERENCES person_organization(id),
 ADD CONSTRAINT fk_point_ledger_member FOREIGN KEY(membership_id) REFERENCES person_membership(id),
 ADD CONSTRAINT ck_point_ledger_basis CHECK((accepting_organization_id IS NULL AND membership_id IS NULL) OR (accepting_organization_id IS NOT NULL AND membership_id IS NOT NULL)),
 ADD KEY idx_point_ledger_accepting(accepting_organization_id,kind,id);
ALTER TABLE point_authorization_audit DROP CHECK point_authorization_audit_chk_1,
 ADD CONSTRAINT ck_point_audit_operation CHECK(operation_name IN ('CREATE','REVIEW','REVERSE','CLOSE'));

-- Trusted read-only qualification source. No public writer or qualification seed is introduced.
-- Actual organization admission and its maintenance authority require explicit owner approval.
CREATE TABLE point_acceptance_evidence (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY,
 organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 verification VARCHAR(16) NOT NULL, enabled BOOLEAN NULL,
 approval_reference VARCHAR(256) NOT NULL, recorded_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_point_evidence_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT ck_point_evidence_state CHECK((verification='VERIFIED' AND enabled IS NOT NULL)
  OR (verification IN ('UNKNOWN','DISPUTED') AND enabled IS NULL)),
 CONSTRAINT ck_point_evidence_reference CHECK(CHAR_LENGTH(approval_reference)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE point_organization_acceptance (
 organization_id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY,
 evidence_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 CONSTRAINT fk_point_qualification_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT fk_point_qualification_evidence FOREIGN KEY(evidence_id) REFERENCES point_acceptance_evidence(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Independent trusted completeness claim. Local positive membership facts do not create this row.
CREATE TABLE person_roster_completeness (
 organization_id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY,
 verification VARCHAR(16) NOT NULL, version CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 membership_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 source_reference VARCHAR(256) NOT NULL,
 CONSTRAINT fk_person_roster_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT ck_person_roster_state CHECK((verification='VERIFIED' AND membership_digest IS NOT NULL)
  OR (verification IN ('UNKNOWN','DISPUTED') AND membership_digest IS NULL)),
 CONSTRAINT ck_person_roster_source CHECK(CHAR_LENGTH(source_reference)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
