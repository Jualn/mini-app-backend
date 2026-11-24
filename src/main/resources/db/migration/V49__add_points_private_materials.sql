-- Dedicated private POINTS material lifecycle; no public Attachment reuse.
CREATE TABLE media_point_upload (
 upload_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 owner_user_id BIGINT UNSIGNED NOT NULL, request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii NOT NULL, expires_at DATETIME(6) NOT NULL,
 UNIQUE KEY uq_point_upload_request(owner_user_id,request_key), FOREIGN KEY(owner_user_id) REFERENCES user_profile(id)
);
CREATE TABLE media_point_file (
 file_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 upload_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, file_index INT NOT NULL,
 owner_user_id BIGINT UNSIGNED NOT NULL, file_name VARCHAR(255) NOT NULL, kind VARCHAR(8) NOT NULL,
 content_type VARCHAR(128) NOT NULL, size_bytes INT NOT NULL,
 source_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 retained_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) NOT NULL, upload_expires_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NULL, unbound_expires_at DATETIME(6) NULL, application_id BIGINT UNSIGNED NULL,
 cleanup_after DATETIME(6) NOT NULL,
 UNIQUE KEY uq_point_upload_index(upload_id,file_index), UNIQUE KEY uq_point_source(source_key), UNIQUE KEY uq_point_retained(retained_key),
 KEY ix_point_media_cleanup(state,cleanup_after), KEY ix_point_media_application(application_id),
 FOREIGN KEY(upload_id) REFERENCES media_point_upload(upload_id), FOREIGN KEY(owner_user_id) REFERENCES user_profile(id),
 FOREIGN KEY(application_id) REFERENCES point_application(id),
 CHECK(file_index BETWEEN 0 AND 8), CHECK(size_bytes BETWEEN 1 AND 10485760),
 CHECK(kind IN ('IMAGE','PDF','WORD')), CHECK(state IN ('UPLOADING','REGISTERED','BOUND','CLEANING','DELETED')),
 CHECK((state='BOUND' AND application_id IS NOT NULL AND unbound_expires_at IS NULL) OR (state<>'BOUND' AND application_id IS NULL))
);
CREATE TABLE media_point_receipt (
 owner_user_id BIGINT UNSIGNED NOT NULL, request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 fingerprint CHAR(64) CHARACTER SET ascii NOT NULL, file_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_json JSON NOT NULL, PRIMARY KEY(owner_user_id,request_key), FOREIGN KEY(file_id) REFERENCES media_point_file(file_id)
);
CREATE TABLE media_point_access_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, actor_user_id BIGINT UNSIGNED NOT NULL, file_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 application_id BIGINT UNSIGNED NOT NULL, accessed_at DATETIME(6) NOT NULL,
 FOREIGN KEY(file_id) REFERENCES media_point_file(file_id), FOREIGN KEY(application_id) REFERENCES point_application(id)
);
