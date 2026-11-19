-- Formal binding no longer depends on a source entry or organization.
ALTER TABLE party_identity_application
 MODIFY roster_entry_id CHAR(36) NULL COMMENT 'Optional legacy roster provenance; not a binding requirement',
 MODIFY organization_id CHAR(36) NULL COMMENT 'Optional legacy organization provenance; not a binding requirement';
ALTER TABLE party_roster_import
 MODIFY workbook MEDIUMBLOB NULL COMMENT 'Encrypted temporary workbook; purged at retention deadline',
 ADD namespace VARCHAR(128) NOT NULL DEFAULT 'CAMPUS_STUDENT_NUMBER' COMMENT 'Provider identifier namespace fixed at upload',
 ADD file_retain_until DATETIME(6) NULL COMMENT 'UTC immutable ciphertext retention deadline';
UPDATE party_roster_import i JOIN person_foundation_event e ON e.id=i.last_event_id
 SET i.file_retain_until=CASE WHEN i.state='COMMITTED' THEN e.recorded_at + INTERVAL 24 HOUR ELSE i.expires_at + INTERVAL 24 HOUR END;
ALTER TABLE party_roster_import
 MODIFY file_retain_until DATETIME(6) NOT NULL COMMENT 'UTC immutable ciphertext retention deadline',
 ADD KEY idx_roster_file_retention(file_retain_until,id);

-- Legacy completeness lacked auditable confirmation metadata. It cannot authorize A7.
ALTER TABLE person_roster_completeness
 ADD status VARCHAR(16) NOT NULL DEFAULT 'UNCONFIRMED' COMMENT 'UNCONFIRMED, COMPLETE, INVALIDATED or REVOKED',
 ADD confirmed_membership_version CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Irreversible membership history and boundary digest confirmed by operator',
 ADD confirmed_by BIGINT UNSIGNED NULL COMMENT 'Confirming User',
 ADD confirmed_at DATETIME(6) NULL COMMENT 'UTC confirmation time',
 ADD last_change_id CHAR(36) NULL COMMENT 'Permanent completeness event ID',
 ADD revision BIGINT NOT NULL DEFAULT 0 COMMENT 'Completeness command and invalidation sequence';
UPDATE person_roster_completeness SET verification='UNKNOWN',membership_digest=NULL;
ALTER TABLE person_roster_completeness COMMENT='Explicit organization completeness; independently confirmed, never implied by imports';

ALTER TABLE person_foundation_event DROP CHECK ck_person_event_type,
 ADD CONSTRAINT ck_person_event_type CHECK(aggregate_type IN ('PERSON','ORGANIZATION','SCOPE','BOOTSTRAP','ROSTER_IMPORT','ROSTER_ENTRY','PARTY_APPLICATION','MENTOR','PROFILE','MAJOR_CLASS','IDENTIFIER','BINDING','ROSTER_COMPLETE','ROSTER_TRACE'));
ALTER TABLE person_foundation_event DROP CHECK ck_person_event_source,
 ADD CONSTRAINT ck_person_event_source CHECK(source IN ('LOCAL_MANUAL','LOCAL_MAINTENANCE','APPROVED_BOOTSTRAP')
 OR (source='ROOT_OWNER' AND aggregate_type='SCOPE')
 OR (source='ROSTER_EXACT_MATCH' AND aggregate_type IN ('PERSON','ROSTER_ENTRY','PARTY_APPLICATION'))
 OR (source='ROSTER_IMPORT' AND aggregate_type='PERSON')
 OR (source='ADMIN_VERIFIED_SOURCE' AND aggregate_type IN ('ROSTER_IMPORT','ROSTER_ENTRY','MENTOR','ROSTER_TRACE')));

ALTER TABLE person_roster_completeness
 ADD CONSTRAINT fk_roster_confirming_user FOREIGN KEY(confirmed_by) REFERENCES user_profile(id),
 ADD CONSTRAINT fk_roster_completeness_event FOREIGN KEY(last_change_id) REFERENCES person_foundation_event(id),
 ADD CONSTRAINT ck_roster_completeness_proof CHECK(
  (status='UNCONFIRMED' AND revision=0 AND confirmed_membership_version IS NULL AND confirmed_by IS NULL AND confirmed_at IS NULL AND last_change_id IS NULL)
  OR (status IN ('COMPLETE','INVALIDATED','REVOKED') AND revision>0 AND confirmed_membership_version IS NOT NULL AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL AND last_change_id IS NOT NULL));
