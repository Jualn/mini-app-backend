-- Preserve past isolated evidence; new effects identify the user-confirmed local policy.
ALTER TABLE point_authorization_audit DROP CHECK point_authorization_audit_chk_4,
 ADD CONSTRAINT ck_point_audit_policy_source CHECK(policy_source IN
 ('ISOLATED_CANDIDATE_POLICY','PROPOSED_POLICY_CLOSED','CONFIRMED_LOCAL_PARTY_V1'));
