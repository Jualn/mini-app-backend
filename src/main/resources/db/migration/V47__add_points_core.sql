-- Points V1 core only; private material acceptance remains closed until its owner is ready.
-- UTC DATETIME(6). No existing business rows or identity/role facts are changed.
CREATE TABLE point_subject_lock (
 user_id BIGINT UNSIGNED PRIMARY KEY,
 CONSTRAINT fk_point_subject_user FOREIGN KEY(user_id) REFERENCES user_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE point_application (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 applicant_user_id BIGINT UNSIGNED NOT NULL,
 title VARCHAR(120) NOT NULL,
 description TEXT NOT NULL,
 file_ids JSON NOT NULL,
 status VARCHAR(16) NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 reviewer_user_id BIGINT UNSIGNED NULL,
 review_note VARCHAR(2000) NULL,
 awarded_points INT NULL,
 grant_ledger_id BIGINT UNSIGNED NULL,
 created_at DATETIME(6) NOT NULL,
 reviewed_at DATETIME(6) NULL,
 CONSTRAINT fk_point_app_user FOREIGN KEY(applicant_user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_point_app_reviewer FOREIGN KEY(reviewer_user_id) REFERENCES user_profile(id),
 CONSTRAINT chk_point_app_state CHECK(status IN ('PENDING','APPROVED','REJECTED')),
 CONSTRAINT chk_point_app_files CHECK(JSON_TYPE(file_ids)='ARRAY' AND JSON_LENGTH(file_ids) BETWEEN 1 AND 9),
 CONSTRAINT chk_point_app_review CHECK(
  (status='PENDING' AND reviewer_user_id IS NULL AND review_note IS NULL AND reviewed_at IS NULL AND awarded_points IS NULL AND grant_ledger_id IS NULL)
  OR (status='REJECTED' AND reviewer_user_id IS NOT NULL AND reviewer_user_id<>applicant_user_id AND review_note IS NOT NULL AND reviewed_at IS NOT NULL AND awarded_points IS NULL AND grant_ledger_id IS NULL)
  OR (status='APPROVED' AND reviewer_user_id IS NOT NULL AND reviewer_user_id<>applicant_user_id AND review_note IS NOT NULL AND reviewed_at IS NOT NULL AND awarded_points IS NOT NULL AND awarded_points>0 AND grant_ledger_id IS NOT NULL)),
 KEY idx_point_app_owner(applicant_user_id,id),
 KEY idx_point_app_owner_state(applicant_user_id,status,id),
 KEY idx_point_app_queue(status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE point_ledger (
 id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT UNSIGNED NOT NULL,
 application_id BIGINT UNSIGNED NOT NULL,
 kind VARCHAR(16) NOT NULL,
 points_delta INT NOT NULL,
 operator_user_id BIGINT UNSIGNED NOT NULL,
 reason VARCHAR(2000) NOT NULL,
 original_ledger_id BIGINT UNSIGNED NULL,
 created_at DATETIME(6) NOT NULL,
 trace_id VARCHAR(64) NULL,
 -- NULL for REVERSAL: multiple independent reversals do not collide in this index.
 grant_application_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN kind='GRANT' THEN application_id ELSE NULL END) STORED,
 UNIQUE KEY uk_point_grant_application(grant_application_id),
 UNIQUE KEY uk_point_reversal_original(original_ledger_id),
 CONSTRAINT fk_point_ledger_user FOREIGN KEY(user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_point_ledger_operator FOREIGN KEY(operator_user_id) REFERENCES user_profile(id),
 CONSTRAINT fk_point_ledger_app FOREIGN KEY(application_id) REFERENCES point_application(id),
 CONSTRAINT fk_point_ledger_original FOREIGN KEY(original_ledger_id) REFERENCES point_ledger(id),
 CONSTRAINT chk_point_ledger_kind CHECK(
  (kind='GRANT' AND points_delta>0 AND original_ledger_id IS NULL)
  OR (kind='REVERSAL' AND points_delta BETWEEN -2147483647 AND -1 AND original_ledger_id IS NOT NULL)),
 CONSTRAINT chk_point_ledger_operator CHECK(user_id<>operator_user_id),
 KEY idx_point_ledger_user(user_id,id),
 KEY idx_point_ledger_user_kind(user_id,kind,id),
 KEY idx_point_ledger_app(application_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE point_application ADD CONSTRAINT fk_point_app_grant FOREIGN KEY(grant_ledger_id) REFERENCES point_ledger(id);

CREATE TABLE point_request (
 actor_user_id BIGINT UNSIGNED NOT NULL,
 session_kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 operation_name VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target_id BIGINT UNSIGNED NOT NULL,
 idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL,
 PRIMARY KEY(actor_user_id,session_kind,operation_name,target_id,idempotency_key),
 CONSTRAINT fk_point_request_actor FOREIGN KEY(actor_user_id) REFERENCES user_profile(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
