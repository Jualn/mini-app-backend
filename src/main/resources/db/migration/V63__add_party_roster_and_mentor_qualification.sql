-- No accounts, Person facts, qualifications or grants are backfilled.
-- Existing ASCII command keys remain byte-for-byte unchanged; new Contract keys allow Unicode.
ALTER TABLE person_command_receipt MODIFY command_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL;
-- Only upload reservations may precede their successful event. Existing completed receipts stay unchanged.
ALTER TABLE person_command_receipt DROP CHECK ck_person_receipt_status,
 MODIFY event_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 ADD CONSTRAINT ck_person_receipt_status CHECK((status IN (200,201) AND event_id IS NOT NULL)
 OR (status=202 AND operation='createPartyRosterImport' AND event_id IS NULL));
ALTER TABLE person_foundation_event DROP CHECK ck_person_event_type,
 DROP CHECK ck_person_event_source,
 ADD CONSTRAINT ck_person_event_type CHECK(aggregate_type IN ('PERSON','ORGANIZATION','SCOPE','BOOTSTRAP','ROSTER_IMPORT','ROSTER_ENTRY','PARTY_APPLICATION','MENTOR')),
 ADD CONSTRAINT ck_person_event_source CHECK(source IN ('LOCAL_MANUAL','LOCAL_MAINTENANCE','APPROVED_BOOTSTRAP')
 OR (source='ROOT_OWNER' AND aggregate_type='SCOPE')
 OR (source='ROSTER_EXACT_MATCH' AND aggregate_type IN ('PERSON','ROSTER_ENTRY','PARTY_APPLICATION'))
 OR (source='ADMIN_VERIFIED_SOURCE' AND aggregate_type IN ('ROSTER_IMPORT','ROSTER_ENTRY','MENTOR'))),
 MODIFY aggregate_type VARCHAR(24) NOT NULL,
 ADD verification_method VARCHAR(32) NULL,
 ADD source_reference VARCHAR(256) NULL,
 ADD source_version BIGINT NULL,
 ADD source_approver BIGINT UNSIGNED NULL,
 ADD CONSTRAINT fk_person_event_source_approver FOREIGN KEY(source_approver) REFERENCES user_profile(id);

-- Person requires a User; these source rows deliberately do not.
-- Workbook and parsed private values use application AES-256-GCM; indexes use HMAC-SHA256.
CREATE TABLE party_roster_import (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, creator BIGINT UNSIGNED NOT NULL,
 source_reference VARCHAR(256) NOT NULL, file_fingerprint CHAR(64) NOT NULL,
 workbook MEDIUMBLOB NOT NULL, columns_json JSON NOT NULL, mapping_json JSON NULL,
 preview LONGBLOB NULL, state VARCHAR(24) NOT NULL, revision BIGINT NOT NULL,
 row_count INT NULL, invalid_row_count INT NULL, imported_count INT NULL,
 expires_at DATETIME(6) NOT NULL, approved_by BIGINT UNSIGNED NULL,
 last_event_id CHAR(36) NOT NULL,
 CONSTRAINT fk_roster_import_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT fk_roster_import_creator FOREIGN KEY(creator) REFERENCES user_profile(id),
 CONSTRAINT fk_roster_import_approver FOREIGN KEY(approved_by) REFERENCES user_profile(id),
 CONSTRAINT fk_roster_import_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_roster_import_state CHECK(state IN ('UPLOADED','PREVIEW_READY','COMMITTED')),
 CONSTRAINT ck_roster_import_revision CHECK(revision>0),
 KEY idx_roster_import_owner(creator,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE party_roster_entry (
 id CHAR(36) PRIMARY KEY, import_id CHAR(36) NOT NULL, organization_id CHAR(36) NOT NULL,
 private_values BLOB NOT NULL, student_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_stage_id VARCHAR(64) NOT NULL, baseline_status VARCHAR(16) NOT NULL,
 linked_user_id BIGINT UNSIGNED NULL, revision BIGINT NOT NULL,
 source_reference VARCHAR(256) NOT NULL, source_approver BIGINT UNSIGNED NOT NULL,
 last_event_id CHAR(36) NOT NULL,
 active_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN baseline_status='ACTIVE' THEN student_digest ELSE NULL END) STORED,
 UNIQUE KEY uk_roster_active_student(active_digest),
 UNIQUE KEY uk_roster_linked_user(linked_user_id),
 KEY idx_roster_entry_org(organization_id,id),
 CONSTRAINT fk_roster_entry_import FOREIGN KEY(import_id) REFERENCES party_roster_import(id),
 CONSTRAINT fk_roster_entry_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT fk_roster_entry_user FOREIGN KEY(linked_user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_roster_entry_approver FOREIGN KEY(source_approver) REFERENCES user_profile(id),
 CONSTRAINT fk_roster_entry_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_roster_entry_state CHECK(baseline_status IN ('ACTIVE','SUSPENDED') AND revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE party_identity_application (
 id CHAR(36) PRIMARY KEY, applicant BIGINT UNSIGNED NOT NULL, roster_entry_id CHAR(36) NOT NULL,
 organization_id CHAR(36) NOT NULL, source_version BIGINT NOT NULL,
 submitted_at DATETIME(6) NOT NULL, last_event_id CHAR(36) NOT NULL,
 UNIQUE KEY uk_party_application_user(applicant), UNIQUE KEY uk_party_application_entry(roster_entry_id),
 CONSTRAINT fk_party_application_user FOREIGN KEY(applicant) REFERENCES user_profile(id),
 CONSTRAINT fk_party_application_entry FOREIGN KEY(roster_entry_id) REFERENCES party_roster_entry(id),
 CONSTRAINT fk_party_application_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Neither RBAC nor Person identity is a mentor business qualification.
CREATE TABLE mentor_profile (
 user_id BIGINT UNSIGNED PRIMARY KEY, real_name VARCHAR(100) NOT NULL,
 position VARCHAR(200) NOT NULL, affiliation VARCHAR(200) NOT NULL,
 revision BIGINT NOT NULL, qualification_id CHAR(36) NULL,
 qualification_state VARCHAR(16) NULL, valid_from DATETIME(6) NULL, valid_until DATETIME(6) NULL,
 verified_at DATETIME(6) NULL, verified_by BIGINT UNSIGNED NULL,
 verification_reference VARCHAR(256) NULL, last_event_id CHAR(36) NOT NULL,
 CONSTRAINT fk_mentor_user FOREIGN KEY(user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_mentor_verifier FOREIGN KEY(verified_by) REFERENCES user_profile(id),
 CONSTRAINT fk_mentor_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT fk_mentor_qualification FOREIGN KEY(qualification_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_mentor_revision CHECK(revision>0),
 CONSTRAINT ck_mentor_qualification CHECK(
 (qualification_id IS NULL AND qualification_state IS NULL AND valid_from IS NULL AND valid_until IS NULL AND verified_at IS NULL AND verified_by IS NULL AND verification_reference IS NULL)
 OR (qualification_id IS NOT NULL AND qualification_state IN ('APPROVED','REVOKED') AND valid_from IS NOT NULL AND verified_at IS NOT NULL AND verified_by IS NOT NULL AND verification_reference IS NOT NULL)),
 CONSTRAINT ck_mentor_period CHECK(valid_until IS NULL OR valid_until>valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
