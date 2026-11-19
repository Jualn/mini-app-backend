-- ROOT owner issues explicit OPERATE scopes; other role definitions remain unchanged.
ALTER TABLE person_scope_grant DROP CHECK ck_person_scope_source,
 ADD CONSTRAINT ck_person_scope_source CHECK(
 (mode='ADMINISTER' AND source='APPROVED_BOOTSTRAP' AND authority_grant_id IS NULL)
 OR (mode='OPERATE' AND source='LOCAL_MAINTENANCE' AND authority_grant_id IS NOT NULL)
 OR (mode='OPERATE' AND source='ROOT_OWNER' AND authority_grant_id IS NULL));

ALTER TABLE person_foundation_event DROP CHECK ck_person_event_source,
 ADD CONSTRAINT ck_person_event_source CHECK(source IN ('LOCAL_MANUAL','LOCAL_MAINTENANCE','APPROVED_BOOTSTRAP')
 OR (source='ROOT_OWNER' AND aggregate_type='SCOPE'));
