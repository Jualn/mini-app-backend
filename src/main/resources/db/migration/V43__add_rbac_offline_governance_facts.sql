-- Structure only: no trusted executor, environment marker, catalog activation or personnel change.
-- Offline facts are not ordinary HTTP GRANT/REVOKE facts.
CREATE TABLE rbac_offline_operation (
  id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  environment_id VARCHAR(96) NOT NULL,
  kind VARCHAR(24) NOT NULL,
  executor_ref VARCHAR(128) NOT NULL,
  approval_ref VARCHAR(500) NOT NULL,
  approval_sha256 CHAR(64) NOT NULL,
  request_fingerprint CHAR(64) NOT NULL,
  request_json JSON NOT NULL,
  approvals_json JSON NOT NULL,
  snapshot_version SMALLINT UNSIGNED NOT NULL,
  receipt JSON NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  CONSTRAINT ck_rbac_offline_kind CHECK (kind IN ('BOOTSTRAP','SOURCE_SWITCH','RECOVERY')),
  CONSTRAINT ck_rbac_offline_version CHECK (snapshot_version=1),
  CONSTRAINT ck_rbac_offline_approval CHECK (CHAR_LENGTH(approval_ref)>0 AND JSON_TYPE(approvals_json)='ARRAY' AND JSON_LENGTH(approvals_json)=2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE rbac_offline_effect (
  id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  operation_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  assignment_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  before_snapshot JSON NOT NULL,
  after_snapshot JSON NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rbac_offline_target (operation_id,user_id),
  UNIQUE KEY uk_rbac_offline_assignment (assignment_id),
  CONSTRAINT fk_rbac_offline_operation FOREIGN KEY (operation_id) REFERENCES rbac_offline_operation(id),
  CONSTRAINT fk_rbac_offline_user FOREIGN KEY (user_id) REFERENCES user_profile(id),
  CONSTRAINT fk_rbac_offline_assignment FOREIGN KEY (assignment_id) REFERENCES rbac_user_role_assignment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
