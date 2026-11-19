-- Additive only: old logs and successful idempotency receipts retain their identities.
-- No historical submission version or snapshot can be inferred from today's content.
CREATE TABLE content_safety_review (
 task_id BIGINT UNSIGNED NOT NULL,
 target_type TINYINT NOT NULL,
 target_id BIGINT UNSIGNED NOT NULL,
 submission_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 scene VARCHAR(32) NOT NULL,
 resource_namespace VARCHAR(32) NOT NULL,
 business_revision BIGINT UNSIGNED NOT NULL,
 snapshot_title VARCHAR(512) NOT NULL,
 snapshot_content MEDIUMTEXT NOT NULL,
 snapshot_attachments JSON NOT NULL,
 expected_checks INT UNSIGNED NOT NULL,
 state VARCHAR(24) NOT NULL,
 previous_task_id BIGINT UNSIGNED NULL,
 superseded_by_task_id BIGINT UNSIGNED NULL,
 decision_origin VARCHAR(16) NULL,
 decided_at DATETIME NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(task_id),
 UNIQUE KEY uk_safety_submission(submission_version),
 KEY idx_safety_target(target_type,target_id,task_id),
 KEY idx_safety_queue(resource_namespace,state,created_at,task_id),
 CONSTRAINT fk_safety_identity FOREIGN KEY(task_id) REFERENCES content_audit_log(id),
 CONSTRAINT fk_safety_previous FOREIGN KEY(previous_task_id) REFERENCES content_safety_review(task_id),
 CONSTRAINT fk_safety_successor FOREIGN KEY(superseded_by_task_id) REFERENCES content_safety_review(task_id),
 CONSTRAINT ck_safety_state CHECK(state IN ('checking','check-failed','pending','approved','rejected','superseded','cancelled','invalidated')),
 CONSTRAINT ck_safety_checks CHECK(expected_checks > 0),
 CONSTRAINT ck_safety_scene CHECK((target_type=1 AND scene='post' AND resource_namespace='POST') OR
  (target_type=4 AND ((scene='post-comment' AND resource_namespace='POST') OR
   (scene='activity-comment' AND resource_namespace='ACTIVITY') OR (scene='public-event-comment' AND resource_namespace='PUBLIC_EVENT'))))
) ENGINE=InnoDB;

CREATE TABLE content_safety_check (
 audit_log_id BIGINT UNSIGNED NOT NULL,
 task_id BIGINT UNSIGNED NOT NULL,
 input_reference VARCHAR(512) NOT NULL,
 execution_state VARCHAR(16) NOT NULL DEFAULT 'queued',
 result VARCHAR(16) NOT NULL DEFAULT 'unknown',
 provider_facts JSON NULL,
 supersedes_check_id BIGINT UNSIGNED NULL,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(audit_log_id),
 KEY idx_safety_check_round(task_id,audit_log_id),
 UNIQUE KEY uk_safety_check_successor(supersedes_check_id),
 CONSTRAINT fk_safety_check_log FOREIGN KEY(audit_log_id) REFERENCES content_audit_log(id),
 CONSTRAINT fk_safety_check_round FOREIGN KEY(task_id) REFERENCES content_safety_review(task_id),
 CONSTRAINT fk_safety_check_previous FOREIGN KEY(supersedes_check_id) REFERENCES content_safety_check(audit_log_id),
 CONSTRAINT ck_safety_execution CHECK(execution_state IN ('queued','running','succeeded','failed','unknown')),
 CONSTRAINT ck_safety_result CHECK(result IN ('pass','review','reject','unknown'))
) ENGINE=InnoDB;

ALTER TABLE content_audit_log ADD COLUMN review_task_id BIGINT UNSIGNED NULL,
 ADD KEY idx_audit_review(review_task_id,id),
 ADD CONSTRAINT fk_audit_review FOREIGN KEY(review_task_id) REFERENCES content_safety_review(task_id);

-- At most one manual terminal receipt per known round, in addition to the global request key.
ALTER TABLE content_audit_log ADD COLUMN manual_review_task_id BIGINT UNSIGNED
 GENERATED ALWAYS AS (CASE WHEN audit_source=2 THEN review_task_id ELSE NULL END) STORED,
 ADD UNIQUE KEY uk_manual_review_round(manual_review_task_id);
