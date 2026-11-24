-- Independent subject foundation. No User -> Person backfill, grants or catalog publication.
-- This migration alone is not a consumer cutover. Old business owners must be changed before release.
CREATE TABLE person_profile (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY COMMENT 'Permanent Person identity, independent of User IDs',
 real_name VARCHAR(100) NOT NULL COMMENT 'Authoritative real name, trim only; not unique',
 status VARCHAR(16) NOT NULL COMMENT 'ACTIVE or ARCHIVED; restore does not restore bindings',
 revision BIGINT NOT NULL COMMENT 'Profile representation revision',
 binding_revision BIGINT NOT NULL DEFAULT 0 COMMENT 'Person binding slot revision, independent of profile',
 created_at DATETIME(6) NOT NULL COMMENT 'UTC creation instant',
 updated_at DATETIME(6) NOT NULL COMMENT 'UTC profile mutation instant',
 last_change_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Atomic profile event',
 CONSTRAINT fk_person_profile_event FOREIGN KEY(last_change_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_person_profile_state CHECK(status IN ('ACTIVE','ARCHIVED') AND revision>0 AND binding_revision>=0 AND CHAR_LENGTH(TRIM(real_name))>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Permanent independent people; ordinary accounts need no row';

CREATE TABLE person_major_class_registration (
 person_id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY COMMENT 'Independent Person owner',
 major_class VARCHAR(200) NULL COMMENT 'Shared platform registration, not academic certification',
 last_action VARCHAR(8) NOT NULL COMMENT 'SET or explicit CLEAR',
 source_kind VARCHAR(32) NOT NULL COMMENT 'ADMIN_REGISTRATION or ROSTER_IMPORT',
 source_reference VARCHAR(256) NOT NULL COMMENT 'Private source reference, separately authorized for disclosure',
 confirmed_by BIGINT UNSIGNED NOT NULL COMMENT 'Actual confirming User',
 confirmed_at DATETIME(6) NOT NULL COMMENT 'UTC confirmation instant',
 revision BIGINT NOT NULL COMMENT 'Independent registration revision',
 CONSTRAINT fk_person_registration_person FOREIGN KEY(person_id) REFERENCES person_profile(id),
 CONSTRAINT fk_person_registration_actor FOREIGN KEY(confirmed_by) REFERENCES user_profile(id),
 CONSTRAINT ck_person_registration CHECK(revision>0 AND source_kind IN ('ADMIN_REGISTRATION','ROSTER_IMPORT') AND
  ((last_action='SET' AND major_class IS NOT NULL AND CHAR_LENGTH(TRIM(major_class))>0) OR (last_action='CLEAR' AND major_class IS NULL)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Current shared major/class value and explicit clear provenance';

CREATE TABLE person_identifier (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY COMMENT 'Stable identifier record',
 person_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Permanent Person owner; never transferred',
 identifier_type VARCHAR(32) NOT NULL COMMENT 'Controlled identifier type',
 namespace VARCHAR(128) NOT NULL COMMENT 'Provider configured global uniqueness namespace',
 value_masked VARCHAR(100) NOT NULL COMMENT 'Safe display without complete original value',
 private_value BLOB NOT NULL COMMENT 'Existing security owner AES-GCM envelope',
 verification VARCHAR(16) NOT NULL COMMENT 'UNKNOWN, VERIFIED or DISPUTED',
 status VARCHAR(16) NOT NULL COMMENT 'ACTIVE or REVOKED; revocation does not release values',
 revision BIGINT NOT NULL COMMENT 'Identifier representation revision',
 source_reference VARCHAR(256) NOT NULL COMMENT 'Verification source reference',
 active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN 1 ELSE NULL END) STORED COMMENT 'Only one active identifier per Person/type/namespace',
 UNIQUE KEY uk_person_identifier_slot(person_id,identifier_type,namespace,active_slot),
 CONSTRAINT fk_person_identifier_person FOREIGN KEY(person_id) REFERENCES person_profile(id),
 CONSTRAINT ck_person_identifier CHECK(identifier_type='STUDENT_NUMBER' AND verification IN ('UNKNOWN','VERIFIED','DISPUTED') AND status IN ('ACTIVE','REVOKED') AND revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Person identifiers; original values never ordinary projections';

CREATE TABLE person_identifier_occupation (
 identifier_type VARCHAR(32) NOT NULL COMMENT 'Controlled identifier type',
 namespace VARCHAR(128) NOT NULL COMMENT 'Provider uniqueness namespace; binary comparison',
 value_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Purpose separated HMAC over trim-only value',
 person_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Permanent owner, retained after correction/revocation',
 PRIMARY KEY(identifier_type,namespace,value_digest),
 CONSTRAINT fk_person_occupation_person FOREIGN KEY(person_id) REFERENCES person_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Permanent identifier value occupancy; database arbitrates concurrent claims';

CREATE TABLE person_identifier_value_history (
 identifier_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Identifier whose protected version is retained',
 revision BIGINT NOT NULL COMMENT 'Immutable identifier version',
 private_value BLOB NOT NULL COMMENT 'Purpose-bound AES-GCM original value envelope',
 PRIMARY KEY(identifier_id,revision),
 CONSTRAINT fk_person_identifier_history FOREIGN KEY(identifier_id) REFERENCES person_identifier(id),
 CONSTRAINT ck_person_identifier_history CHECK(revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Protected original values for authorized historical verification';

CREATE TABLE person_account_binding (
 id CHAR(36) COLLATE utf8mb4_0900_bin PRIMARY KEY COMMENT 'Immutable binding identity; ended IDs never reactivate',
 user_id BIGINT UNSIGNED NOT NULL COMMENT 'Login account, not Person identity',
 person_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Permanent independent Person',
 status VARCHAR(16) NOT NULL COMMENT 'ACTIVE or ENDED',
 revision BIGINT NOT NULL COMMENT 'Binding resource revision',
 started_at DATETIME(6) NOT NULL COMMENT 'UTC binding start',
 verification_method VARCHAR(32) NOT NULL COMMENT 'ADMIN_MANUAL or ROSTER_EXACT_MATCH',
 source_reference VARCHAR(256) NOT NULL COMMENT 'Trusted binding source',
 started_by BIGINT UNSIGNED NOT NULL COMMENT 'Actual binding operator User',
 ended_at DATETIME(6) NULL COMMENT 'UTC end, never cleared',
 ended_by BIGINT UNSIGNED NULL COMMENT 'Actual end operator User',
 end_reason VARCHAR(2000) NULL COMMENT 'Reason binding ended',
 active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN 1 ELSE NULL END) STORED COMMENT 'Nullable history slot; uniqueness on each side',
 UNIQUE KEY uk_binding_user(user_id,active_slot),
 UNIQUE KEY uk_binding_person(person_id,active_slot),
 KEY idx_binding_person_history(person_id,id),
 CONSTRAINT fk_binding_user FOREIGN KEY(user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_binding_person FOREIGN KEY(person_id) REFERENCES person_profile(id),
 CONSTRAINT fk_binding_starter FOREIGN KEY(started_by) REFERENCES user_profile(id),
 CONSTRAINT fk_binding_ender FOREIGN KEY(ended_by) REFERENCES user_profile(id),
 CONSTRAINT ck_binding_state CHECK(revision>0 AND verification_method IN ('ADMIN_MANUAL','ROSTER_EXACT_MATCH') AND
  ((status='ACTIVE' AND ended_at IS NULL AND ended_by IS NULL AND end_reason IS NULL) OR
   (status='ENDED' AND ended_at IS NOT NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL AND ended_at>=started_at)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Current account/Person relation plus immutable ended intervals';

CREATE TABLE person_account_binding_slot (
 user_id BIGINT UNSIGNED PRIMARY KEY COMMENT 'Account side slot, ordinary accounts need no Person',
 revision BIGINT NOT NULL COMMENT 'Monotonic slot version; absent row has version zero',
 CONSTRAINT fk_binding_slot_user FOREIGN KEY(user_id) REFERENCES user_profile(id),
 CONSTRAINT ck_binding_slot_version CHECK(revision>0)
) ENGINE=InnoDB COMMENT='Independent account binding slot version; not account profile version';

-- Shared permanent journal/receipt protocol, not a second audit authority.
ALTER TABLE person_foundation_event DROP CHECK ck_person_event_type,
 ADD CONSTRAINT ck_person_event_type CHECK(aggregate_type IN ('PERSON','ORGANIZATION','SCOPE','BOOTSTRAP','ROSTER_IMPORT','ROSTER_ENTRY','PARTY_APPLICATION','MENTOR','PROFILE','MAJOR_CLASS','IDENTIFIER','BINDING')),
 ADD COLUMN person_id CHAR(36) COLLATE utf8mb4_0900_bin NULL COMMENT 'Independent Person for PROFILE/MAJOR_CLASS/IDENTIFIER/BINDING events',
 ADD COLUMN before_version VARCHAR(128) NULL COMMENT 'Protected resource version before mutation',
 ADD COLUMN after_version VARCHAR(128) NULL COMMENT 'Protected resource version after mutation',
 ADD KEY idx_independent_person_history(person_id,recorded_at,id);
-- person_id is application-validated in the same transaction: the creation event precedes the
-- profile insert and profile.last_change_id references that event, so an FK would form a cycle.
ALTER TABLE person_command_receipt MODIFY command_key VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Original caller key; identity is actor/operation/target/key',
 DROP CHECK ck_person_receipt_status,
 ADD CONSTRAINT ck_person_receipt_status CHECK((status IN (200,201) AND event_id IS NOT NULL)
 OR (status=202 AND operation='createPartyRosterImport' AND event_id IS NULL)
 OR (status=200 AND operation='updatePersonMajorClassRegistration' AND event_id IS NULL));
