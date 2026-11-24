CREATE TABLE media_learning_upload (
 upload_id CHAR(36) NOT NULL COMMENT 'Opaque server upload allocation',
 owner_user_id BIGINT NOT NULL COMMENT 'Authenticated uploader User at allocation',
 assignment_id BIGINT NOT NULL COMMENT 'Stable LearningTask responsibility assignment',
 request_key VARCHAR(128) NOT NULL COMMENT 'Uploader and assignment scoped idempotency key',
 fingerprint CHAR(64) NOT NULL COMMENT 'SHA256 of ordered immutable file declarations',
 expires_at DATETIME(6) NOT NULL COMMENT 'Fixed upload credential upper bound, Asia/Shanghai',
 PRIMARY KEY(upload_id), UNIQUE KEY uk_learning_upload_request(owner_user_id,assignment_id,request_key)
) ENGINE=InnoDB COMMENT='LearningTask private upload allocations; never stores STS credentials';

CREATE TABLE media_learning_file (
 file_id BIGINT NOT NULL COMMENT 'Private file identity, independent of public Attachment',
 assignment_id BIGINT NOT NULL COMMENT 'Immutable LearningTask assignment owner',
 owner_user_id BIGINT NOT NULL COMMENT 'Actual original uploader User',
 upload_id CHAR(36) NOT NULL COMMENT 'Server allocation defining exact source key',
 file_index INT NOT NULL COMMENT 'Zero based declaration position',
 file_name VARCHAR(255) NOT NULL COMMENT 'Declared basename verified against accepted format',
 kind VARCHAR(16) NOT NULL COMMENT 'IMAGE VIDEO PDF WORD',
 content_type VARCHAR(128) NOT NULL COMMENT 'Verified declaration MIME',
 size_bytes INT NOT NULL COMMENT 'Exact immutable byte size; video max 200MiB, other formats 10MiB',
 source_key VARCHAR(300) NOT NULL COMMENT 'Private client write candidate; never exposed as read URL',
 retained_key VARCHAR(300) NOT NULL COMMENT 'Server only immutable verified snapshot key',
 state VARCHAR(16) NOT NULL COMMENT 'UPLOADING REGISTERED BOUND CLEANING DELETED; BOUND never recycled',
 submission_id BIGINT NULL COMMENT 'Formal immutable LearningTask submission retaining this file',
 safety_audit_log_id BIGINT NULL COMMENT 'Single automatic image safety reservation; unknown outcome never resubmitted',
 created_at DATETIME(6) NOT NULL COMMENT 'Allocation time or successful registration time, Asia/Shanghai',
 upload_expires_at DATETIME(6) NOT NULL COMMENT 'Fixed latest upload credential expiry, Asia/Shanghai',
 unbound_expires_at DATETIME(6) NULL COMMENT 'Registered candidate expires 24h after registration; NULL after takeover',
 cleanup_after DATETIME(6) NOT NULL COMMENT 'Unbound cleanup eligibility, Asia/Shanghai; ignored for formal BOUND history',
 PRIMARY KEY(file_id), UNIQUE KEY uk_learning_file_index(upload_id,file_index), UNIQUE KEY uk_learning_file_retained(retained_key),
 KEY idx_learning_file_cleanup(state,cleanup_after,file_id),
 CONSTRAINT fk_learning_file_upload FOREIGN KEY(upload_id) REFERENCES media_learning_upload(upload_id),
 CONSTRAINT ck_learning_file_state CHECK(state IN ('UPLOADING','REGISTERED','BOUND','CLEANING','DELETED') AND file_index BETWEEN 0 AND 8 AND size_bytes BETWEEN 1 AND 209715200),
 CONSTRAINT ck_learning_file_bound CHECK((state='BOUND' AND submission_id IS NOT NULL AND unbound_expires_at IS NULL) OR (state<>'BOUND' AND submission_id IS NULL))
) ENGINE=InnoDB COMMENT='Media owned private LearningTask candidates and permanently retained formal submission bytes';

CREATE TABLE media_learning_receipt (
 owner_user_id BIGINT NOT NULL COMMENT 'Actual authenticated uploader',
 assignment_id BIGINT NOT NULL COMMENT 'Stable LearningTask assignment',
 request_key VARCHAR(128) NOT NULL COMMENT 'Registration idempotency key in uploader and assignment scope',
 fingerprint CHAR(64) NOT NULL COMMENT 'SHA256 of uploadId and fileIndex',
 result_json JSON NOT NULL COMMENT 'Original successful registration metadata; no credential or signed URL',
 PRIMARY KEY(owner_user_id,assignment_id,request_key)
) ENGINE=InnoDB COMMENT='Original private learning file registration receipts; unaffected by formal takeover';
