-- Append authorization evidence without rewriting V47 accounting or V48 owner facts.
CREATE TABLE point_authorization_audit (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 actor_user_id BIGINT UNSIGNED NOT NULL,
 target_user_id BIGINT UNSIGNED NOT NULL,
 operation_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 application_id BIGINT UNSIGNED NOT NULL,
 ledger_id BIGINT UNSIGNED NULL,
 request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tx_context_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 authority_epoch BIGINT NOT NULL,
 evaluated_at DATETIME(6) NOT NULL,
 checked_at DATETIME(6) NOT NULL,
 policy_source VARCHAR(32) CHARACTER SET ascii NOT NULL,
 UNIQUE KEY uq_point_authority_command(actor_user_id,operation_name,application_id,request_key),
 KEY ix_point_authority_application(application_id,id),
 FOREIGN KEY(actor_user_id) REFERENCES user_profile(id),
 FOREIGN KEY(target_user_id) REFERENCES user_profile(id),
 FOREIGN KEY(application_id) REFERENCES point_application(id),
 FOREIGN KEY(ledger_id) REFERENCES point_ledger(id),
 CHECK(operation_name IN ('CREATE','REVIEW','REVERSE')),
 CHECK(authority_epoch>=0), CHECK(checked_at>=evaluated_at),
 CHECK(policy_source IN ('ISOLATED_CANDIDATE_POLICY','PROPOSED_POLICY_CLOSED'))
);
