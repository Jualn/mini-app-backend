-- Local evidence is independent from complete person membership / complete organization roster.
-- No backfill: existing VERIFIED full sets remain authoritative; missing local evidence stays UNKNOWN.
ALTER TABLE person_foundation_event ADD COLUMN local_organization_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 ADD COLUMN local_before_json JSON NULL, ADD COLUMN local_after_json JSON NULL,
 ADD KEY idx_person_local_history(aggregate_id,local_organization_id,aggregate_version);
CREATE TABLE person_local_membership (
 user_id BIGINT UNSIGNED NOT NULL,
 organization_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 presence VARCHAR(16) NOT NULL,
 membership_id CHAR(36) COLLATE utf8mb4_0900_bin NULL,
 verified_by BIGINT UNSIGNED NOT NULL, verified_at DATETIME(6) NOT NULL,
 last_event_id CHAR(36) COLLATE utf8mb4_0900_bin NOT NULL,
 PRIMARY KEY(user_id,organization_id),
 CONSTRAINT fk_person_local_user FOREIGN KEY(user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_person_local_org FOREIGN KEY(organization_id) REFERENCES person_organization(id),
 CONSTRAINT fk_person_local_membership FOREIGN KEY(membership_id) REFERENCES person_membership(id),
 CONSTRAINT fk_person_local_verifier FOREIGN KEY(verified_by) REFERENCES user_profile(id),
 CONSTRAINT fk_person_local_event FOREIGN KEY(last_event_id) REFERENCES person_foundation_event(id),
 CONSTRAINT ck_person_local_presence CHECK((presence='PRESENT' AND membership_id IS NOT NULL)
  OR (presence='ABSENT' AND membership_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
