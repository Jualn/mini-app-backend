-- V66: replace only the retired User-anchored Person storage.
-- V1-V65 are immutable. Manual execution: select the intended database, stop writers,
-- save a recoverable backup, then execute the complete file. No server action is implicit.
-- Existing organizations, Users, account Assignments and independent business scopes remain.
-- Old Person test facts/sources are intentionally retired; no guessed User -> Person backfill.
DELIMITER $$
DROP PROCEDURE IF EXISTS jualn_person_v66_cutover$$
CREATE PROCEDURE jualn_person_v66_cutover()
cutover: BEGIN
 IF DATABASE() IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V66 requires an explicitly selected database'; END IF;
 IF @person_v66_expected_database IS NOT NULL AND @person_v66_expected_database<>DATABASE() THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V66 selected database does not match expected target';
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='person_fact_current' AND column_name='person_id') THEN
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND
    ((table_name IN ('person_fact_current','person_membership','person_local_membership','mentor_profile') AND column_name='user_id')
      OR (table_name='party_roster_entry' AND column_name='linked_user_id') OR (table_name='mentor_profile' AND column_name='real_name'))) THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V66 partial schema: inspect actual constraints before recovery';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_name='fk_learning_submission_binding')
   OR NOT EXISTS(SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_name='fk_point_ledger_submission')
   OR NOT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='person_organization' AND column_name='type') THEN
   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V66 incomplete cutover: preserve the database and inspect recovery from the original backup';
  END IF;
  SELECT 'V66_ALREADY_APPLIED' AS person_cutover_status; LEAVE cutover;
 END IF;
 IF EXISTS(SELECT 1 FROM point_application) OR EXISTS(SELECT 1 FROM point_ledger) OR EXISTS(SELECT 1 FROM point_request) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V66 cannot reinterpret existing Points ownership; retain data and resolve the approved cutover';
 END IF;
 IF EXISTS(SELECT 1 FROM person_scope_grant WHERE target_type='PERSON')
  OR EXISTS(SELECT 1 FROM person_business_scope WHERE JSON_UNQUOTE(JSON_EXTRACT(selection,'$.target.type'))='PERSON' OR JSON_LENGTH(member_bindings)>0)
  OR EXISTS(SELECT 1 FROM rbac_delegation_authority WHERE JSON_LENGTH(member_bindings)>0) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V66 cannot transfer existing Person approvals or membership-bound appointments';
 END IF;
 IF EXISTS(SELECT 1 FROM comment WHERE mentor_evidence IS NOT NULL AND CHAR_LENGTH(mentor_evidence)>0
  AND JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(mentor_evidence),mentor_evidence,'{}'),'$.personId')) IS NULL) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V66 requires an explicit retention plan for legacy formal-answer evidence';
 END IF;
 IF EXISTS(SELECT 1 FROM learning_task WHERE COALESCE(JSON_LENGTH(JSON_EXTRACT(audience,'$.filters')),0)>0
   OR NULLIF(JSON_UNQUOTE(JSON_EXTRACT(audience,'$.authorizedScopeId')),'null') IS NOT NULL) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V66 cannot infer Person ownership for legacy filtered learning audiences';
 END IF;
 DROP TEMPORARY TABLE IF EXISTS person_v66_retired_events;
 CREATE TEMPORARY TABLE person_v66_retired_events(id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY);
 INSERT INTO person_v66_retired_events SELECT id FROM person_foundation_event
 WHERE aggregate_type IN ('PERSON','MENTOR','ROSTER_IMPORT','ROSTER_ENTRY','PARTY_APPLICATION') AND person_id IS NULL;
 IF EXISTS(SELECT 1 FROM person_foundation_event e JOIN person_v66_retired_events parent ON parent.id=e.corrects_event_id
  WHERE e.person_id IS NOT NULL OR e.aggregate_type NOT IN ('PERSON','MENTOR','ROSTER_IMPORT','ROSTER_ENTRY','PARTY_APPLICATION')) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V66 found retained audit evidence depending on retired facts';
 END IF;
 ALTER TABLE point_application DROP FOREIGN KEY fk_point_accepting_member, DROP FOREIGN KEY fk_point_accepting_org;
 ALTER TABLE point_ledger DROP FOREIGN KEY fk_point_ledger_member, DROP FOREIGN KEY fk_point_ledger_accepting;
 DROP TABLE party_identity_application;
 DROP TABLE party_roster_entry;
 DROP TABLE party_roster_import;
 DROP TABLE mentor_profile;
 DROP TABLE person_local_membership;
 DROP TABLE person_membership;
 DROP TABLE person_fact_current;
 DELETE FROM person_roster_completeness;
 DELETE r FROM person_command_receipt r JOIN person_v66_retired_events old ON old.id=r.event_id;
 DELETE FROM person_command_receipt WHERE operation='createPartyRosterImport' AND status=202 AND event_id IS NULL;
 UPDATE person_foundation_event e JOIN person_v66_retired_events old ON old.id=e.id SET e.corrects_event_id=NULL;
 DELETE e FROM person_foundation_event e JOIN person_v66_retired_events old ON old.id=e.id;
 DROP TEMPORARY TABLE person_v66_retired_events;
CREATE TABLE person_fact_current (
 person_id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY, revision BIGINT NOT NULL,
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
 CONSTRAINT fk_person_head_person FOREIGN KEY(person_id) REFERENCES person_profile(id),
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Independent Person owner; User remains operator only';

CREATE TABLE person_membership (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY, person_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 valid_from DATETIME(6) NOT NULL, valid_until DATETIME(6) NULL,
 created_event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 ended_event_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN ended_event_id IS NULL THEN 1 ELSE NULL END) STORED,
 UNIQUE KEY uk_person_member_slot(person_id,organization_id,active_slot),
 KEY idx_person_member_org(organization_id,ended_event_id,person_id),
 CONSTRAINT fk_person_member_person FOREIGN KEY(person_id) REFERENCES person_profile(id),
 CONSTRAINT fk_person_member_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT fk_person_member_created FOREIGN KEY(created_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT fk_person_member_ended FOREIGN KEY(ended_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_person_member_period CHECK(valid_until IS NULL OR valid_until>valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Independent Person owner; User remains operator only';

CREATE TABLE person_local_membership (
 person_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 presence VARCHAR(16) NOT NULL,
 membership_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 verified_by BIGINT UNSIGNED NOT NULL, verified_at DATETIME(6) NOT NULL,
 last_event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 PRIMARY KEY(person_id,organization_id),
 CONSTRAINT fk_person_local_person FOREIGN KEY(person_id) REFERENCES person_profile(id),
 CONSTRAINT fk_person_local_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT fk_person_local_membership FOREIGN KEY(membership_id) REFERENCES person_membership(id),
 CONSTRAINT fk_person_local_verifier FOREIGN KEY(verified_by) REFERENCES user_profile(id),
 CONSTRAINT fk_person_local_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_person_local_presence CHECK((presence='PRESENT' AND membership_id IS NOT NULL)
  OR (presence='ABSENT' AND membership_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Independent Person owner; User remains operator only';

CREATE TABLE party_roster_import (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, creator BIGINT UNSIGNED NOT NULL,
 source_reference VARCHAR(256) NOT NULL, file_fingerprint CHAR(64) NOT NULL,
 workbook MEDIUMBLOB NOT NULL, columns_json JSON NOT NULL, mapping_json JSON NULL,
 preview LONGBLOB NULL, state VARCHAR(24) NOT NULL, revision BIGINT NOT NULL,
 row_count INT NULL, invalid_row_count INT NULL, imported_count INT NULL,
 plan_id CHAR(36) NULL COMMENT 'Opaque preview dependency plan',
 plan_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'HMAC of input, source and current dependencies',
 plan_expires_at DATETIME(6) NULL COMMENT 'UTC right-open plan expiry',
 expires_at DATETIME(6) NOT NULL, approved_by BIGINT UNSIGNED NULL,
 last_event_id CHAR(36) NOT NULL,
 CONSTRAINT fk_roster_import_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT fk_roster_import_creator FOREIGN KEY(creator) REFERENCES user_profile(id),
 CONSTRAINT fk_roster_import_approver FOREIGN KEY(approved_by) REFERENCES user_profile(id),
 CONSTRAINT fk_roster_import_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_roster_import_state CHECK(state IN ('UPLOADED','PREVIEW_READY','COMMITTED')),
 CONSTRAINT ck_roster_import_revision CHECK(revision>0),
 KEY idx_roster_import_owner(creator,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Independent Person owner; User remains operator only';

CREATE TABLE party_roster_entry (
 id CHAR(36) PRIMARY KEY, import_id CHAR(36) NOT NULL, organization_id CHAR(36) NOT NULL,
 private_values BLOB NOT NULL, student_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_stage_id VARCHAR(64) NOT NULL, baseline_status VARCHAR(16) NOT NULL,
 person_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Independent Person established by commit', revision BIGINT NOT NULL,
 source_reference VARCHAR(256) NOT NULL, source_approver BIGINT UNSIGNED NOT NULL,
 last_event_id CHAR(36) NOT NULL,
 active_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN baseline_status='ACTIVE' THEN student_digest ELSE NULL END) STORED,
 UNIQUE KEY uk_roster_active_student(organization_id,active_digest),
 KEY idx_roster_entry_org(organization_id,id),
 CONSTRAINT fk_roster_entry_import FOREIGN KEY(import_id) REFERENCES party_roster_import(id),
 CONSTRAINT fk_roster_entry_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT fk_roster_entry_person FOREIGN KEY(person_id) REFERENCES person_profile(id),
 CONSTRAINT fk_roster_entry_approver FOREIGN KEY(source_approver) REFERENCES user_profile(id),
 CONSTRAINT fk_roster_entry_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_roster_entry_state CHECK(baseline_status IN ('ACTIVE','SUSPENDED') AND revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Independent Person owner; User remains operator only';

CREATE TABLE party_identity_application (
 id CHAR(36) PRIMARY KEY, applicant BIGINT UNSIGNED NOT NULL, roster_entry_id CHAR(36) NOT NULL,
 person_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Person bound by this successful command',
 binding_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Historical binding; never revived',
 organization_id CHAR(36) NOT NULL, source_version BIGINT NOT NULL,
 submitted_at DATETIME(6) NOT NULL, last_event_id CHAR(36) NOT NULL,
 UNIQUE KEY uk_party_application_binding(binding_id),
 CONSTRAINT fk_party_application_person FOREIGN KEY(person_id) REFERENCES person_profile(id),
 CONSTRAINT fk_party_application_binding FOREIGN KEY(binding_id) REFERENCES person_account_binding(id),
 CONSTRAINT fk_party_application_user FOREIGN KEY(applicant) REFERENCES user_profile(id),
 CONSTRAINT fk_party_application_entry FOREIGN KEY(roster_entry_id) REFERENCES party_roster_entry(id),
 CONSTRAINT fk_party_application_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Independent Person owner; User remains operator only';

CREATE TABLE mentor_profile (
 person_id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY,
 position VARCHAR(200) NOT NULL, affiliation VARCHAR(200) NOT NULL,
 revision BIGINT NOT NULL, qualification_id CHAR(36) NULL,
 qualification_state VARCHAR(16) NULL, valid_from DATETIME(6) NULL, valid_until DATETIME(6) NULL,
 verified_at DATETIME(6) NULL, verified_by BIGINT UNSIGNED NULL,
 verification_reference VARCHAR(256) NULL, last_event_id CHAR(36) NOT NULL,
 CONSTRAINT fk_mentor_person FOREIGN KEY(person_id) REFERENCES person_profile(id),
 CONSTRAINT fk_mentor_verifier FOREIGN KEY(verified_by) REFERENCES user_profile(id),
 CONSTRAINT fk_mentor_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT fk_mentor_qualification FOREIGN KEY(qualification_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_mentor_revision CHECK(revision>0),
 CONSTRAINT ck_mentor_qualification CHECK(
 (qualification_id IS NULL AND qualification_state IS NULL AND valid_from IS NULL AND valid_until IS NULL AND verified_at IS NULL AND verified_by IS NULL AND verification_reference IS NULL)
 OR (qualification_id IS NOT NULL AND qualification_state IN ('APPROVED','REVOKED') AND valid_from IS NOT NULL AND verified_at IS NOT NULL AND verified_by IS NOT NULL AND verification_reference IS NOT NULL)),
 CONSTRAINT ck_mentor_period CHECK(valid_until IS NULL OR valid_until>valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Independent Person owner; User remains operator only';

 -- Empty Points aggregates cut over explicitly; original submitter remains an immutable User fact.
 DROP TABLE point_subject_lock;
 CREATE TABLE point_subject_lock (
  person_id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY COMMENT 'Balance serialization subject, independent Person',
  CONSTRAINT fk_point_subject_person FOREIGN KEY(person_id) REFERENCES person_profile(id)
 ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
 ALTER TABLE person_account_binding ADD UNIQUE KEY uk_binding_submission_proof(person_id,id,user_id);
 ALTER TABLE person_membership ADD UNIQUE KEY uk_membership_person_org(id,person_id,organization_id);
 ALTER TABLE point_application
  ADD COLUMN person_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Immutable business subject; never re-resolved from applicant',
  ADD COLUMN submitted_binding_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Exact binding at original submission; ended binding retained',
  MODIFY accepting_organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  MODIFY membership_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  DROP CHECK ck_point_closed,
  ADD CONSTRAINT ck_point_closed CHECK((status='CLOSED' AND closed_at IS NOT NULL AND closed_by_user_id IS NOT NULL AND closure_reason IS NOT NULL)
   OR (status<>'CLOSED' AND closed_at IS NULL AND closed_by_user_id IS NULL AND closure_reason IS NULL)),
  ADD CONSTRAINT fk_point_accepting_org FOREIGN KEY(accepting_organization_id) REFERENCES person_organization(id),
  ADD CONSTRAINT fk_point_submission_binding FOREIGN KEY(person_id,submitted_binding_id,applicant_user_id) REFERENCES person_account_binding(person_id,id,user_id),
  ADD CONSTRAINT fk_point_accepting_member FOREIGN KEY(membership_id,person_id,accepting_organization_id) REFERENCES person_membership(id,person_id,organization_id),
  ADD UNIQUE KEY uk_point_application_proof(id,person_id,applicant_user_id,submitted_binding_id,accepting_organization_id,membership_id),
  ADD KEY idx_point_person_state(person_id,status,id);
 ALTER TABLE point_ledger DROP FOREIGN KEY fk_point_ledger_user, DROP CHECK chk_point_ledger_operator;
 ALTER TABLE point_ledger RENAME COLUMN user_id TO applicant_user_id;
 ALTER TABLE point_ledger
  ADD COLUMN person_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Immutable ledger and balance subject',
  ADD COLUMN submitted_binding_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Original submission binding proof',
  MODIFY accepting_organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  MODIFY membership_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
  ADD CONSTRAINT fk_point_ledger_accepting FOREIGN KEY(accepting_organization_id) REFERENCES person_organization(id),
  ADD CONSTRAINT fk_point_ledger_submitter FOREIGN KEY(applicant_user_id) REFERENCES user_profile(id),
  ADD CONSTRAINT chk_point_ledger_operator CHECK(applicant_user_id<>operator_user_id),
  ADD CONSTRAINT fk_point_ledger_member FOREIGN KEY(membership_id,person_id,accepting_organization_id) REFERENCES person_membership(id,person_id,organization_id),
  ADD CONSTRAINT fk_point_ledger_submission FOREIGN KEY(application_id,person_id,applicant_user_id,submitted_binding_id,accepting_organization_id,membership_id)
   REFERENCES point_application(id,person_id,applicant_user_id,submitted_binding_id,accepting_organization_id,membership_id),
  ADD KEY idx_point_ledger_person(person_id,id);
 -- Existing explicit account responsibilities stay ACCOUNT; no User -> Person inference.
 UPDATE learning_task SET audience=JSON_OBJECT('personIds',JSON_ARRAY(),'accountUserIds',COALESCE(JSON_EXTRACT(audience,'$.userIds'),JSON_ARRAY()),'filters',JSON_ARRAY(),'authorizedScopeId',NULL) WHERE audience IS NOT NULL;
 DELETE FROM learning_task_preview;
 ALTER TABLE learning_task_preview DROP CHECK ck_learning_preview_classification,
  DROP COLUMN user_ids,DROP COLUMN already_assigned_user_ids,
  ADD COLUMN recipients JSON NOT NULL COMMENT 'Frozen subject, delivery proof, availability and assigned classification',
  ADD COLUMN dependency_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Authorization, Person epoch and source dependency fingerprint';
 ALTER TABLE learning_task_request ADD COLUMN contract_revision VARCHAR(16) NOT NULL DEFAULT 'LEGACY_V65'
  COMMENT 'Old command receipts remain retained and cannot be reinterpreted by the breaking Person contract';
 ALTER TABLE learning_task_assignment DROP FOREIGN KEY fk_learning_assignment_user,DROP INDEX uk_learning_task_user;
 ALTER TABLE learning_task_assignment
  MODIFY user_id BIGINT UNSIGNED NULL COMMENT 'ACCOUNT responsibility only; not the current delivery account for PERSON',
  ADD COLUMN subject_type VARCHAR(8) NOT NULL DEFAULT 'ACCOUNT',
  ADD COLUMN person_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  ADD COLUMN confirmed_by_user_id BIGINT UNSIGNED NULL,
  ADD COLUMN confirmed_binding_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  ADD COLUMN responsibility_key VARCHAR(80) COLLATE utf8mb4_0900_bin GENERATED ALWAYS AS (CONCAT(subject_type,':',CASE WHEN subject_type='PERSON' THEN person_id ELSE CAST(user_id AS CHAR) END)) STORED,
  ADD CONSTRAINT fk_learning_assignment_user FOREIGN KEY(user_id) REFERENCES user_profile(id),
  ADD CONSTRAINT fk_learning_assignment_person FOREIGN KEY(person_id) REFERENCES person_profile(id),
  ADD CONSTRAINT fk_learning_confirmation_binding FOREIGN KEY(person_id,confirmed_binding_id,confirmed_by_user_id) REFERENCES person_account_binding(person_id,id,user_id),
  ADD CONSTRAINT ck_learning_subject CHECK((subject_type='PERSON' AND person_id IS NOT NULL AND user_id IS NULL) OR (subject_type='ACCOUNT' AND person_id IS NULL AND user_id IS NOT NULL)),
  ADD UNIQUE KEY uk_learning_responsibility(task_id,responsibility_key),
  ADD UNIQUE KEY uk_learning_assignment_responsibility(id,responsibility_key);
 UPDATE learning_task_assignment SET confirmed_by_user_id=user_id WHERE confirmed_at IS NOT NULL;
 ALTER TABLE learning_task_assignment ADD CONSTRAINT ck_learning_confirmation_proof CHECK(
  (confirmed_at IS NULL AND confirmed_by_user_id IS NULL AND confirmed_binding_id IS NULL)
  OR (confirmed_at IS NOT NULL AND confirmed_by_user_id IS NOT NULL AND
   ((subject_type='ACCOUNT' AND confirmed_by_user_id=user_id AND confirmed_binding_id IS NULL) OR (subject_type='PERSON' AND confirmed_binding_id IS NOT NULL))));
 ALTER TABLE learning_task_submission
  ADD COLUMN submitted_by_user_id BIGINT UNSIGNED NULL,
  ADD COLUMN person_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  ADD COLUMN binding_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
  ADD COLUMN responsibility_key VARCHAR(80) COLLATE utf8mb4_0900_bin NULL;
 UPDATE learning_task_submission s JOIN learning_task_assignment a ON a.id=s.assignment_id
  SET s.submitted_by_user_id=a.user_id,s.responsibility_key=a.responsibility_key;
 ALTER TABLE learning_task_submission
  MODIFY submitted_by_user_id BIGINT UNSIGNED NOT NULL,
  MODIFY responsibility_key VARCHAR(80) COLLATE utf8mb4_0900_bin NOT NULL,
  ADD CONSTRAINT fk_learning_submission_actor FOREIGN KEY(submitted_by_user_id) REFERENCES user_profile(id),
  ADD CONSTRAINT fk_learning_submission_subject FOREIGN KEY(assignment_id,responsibility_key) REFERENCES learning_task_assignment(id,responsibility_key),
  ADD CONSTRAINT fk_learning_submission_binding FOREIGN KEY(person_id,binding_id,submitted_by_user_id) REFERENCES person_account_binding(person_id,id,user_id),
  ADD CONSTRAINT ck_learning_submission_proof CHECK((person_id IS NULL AND binding_id IS NULL AND responsibility_key=CONCAT('ACCOUNT:',submitted_by_user_id))
   OR (person_id IS NOT NULL AND binding_id IS NOT NULL AND responsibility_key=CONCAT('PERSON:',person_id)));
 ALTER TABLE person_organization ADD COLUMN type VARCHAR(32) NOT NULL DEFAULT 'UNCLASSIFIED'
  COMMENT 'Controlled organization classification; never inferred from name or grants',
  ADD CONSTRAINT ck_person_organization_type CHECK(type IN ('PARTY_COMMITTEE','PARTY_GENERAL_BRANCH','PARTY_BRANCH','GENERAL_UNIT','UNCLASSIFIED'));
 ALTER TABLE person_foundation_event DROP CHECK ck_person_event_source,
  ADD CONSTRAINT ck_person_event_source CHECK(source IN ('LOCAL_MANUAL','LOCAL_MAINTENANCE','APPROVED_BOOTSTRAP')
   OR (source='ROOT_OWNER' AND aggregate_type='SCOPE')
   OR (source='ROSTER_EXACT_MATCH' AND aggregate_type IN ('PERSON','ROSTER_ENTRY','PARTY_APPLICATION'))
   OR (source='ROSTER_IMPORT' AND aggregate_type='PERSON')
   OR (source='ADMIN_VERIFIED_SOURCE' AND aggregate_type IN ('ROSTER_IMPORT','ROSTER_ENTRY','MENTOR')));
 UPDATE person_authority_epoch SET epoch=epoch+1 WHERE id=1;
 SELECT 'V66_PERSON_STORAGE_REPLACED' AS person_cutover_status;
END$$
CALL jualn_person_v66_cutover()$$
DROP PROCEDURE jualn_person_v66_cutover$$
DELIMITER ;
