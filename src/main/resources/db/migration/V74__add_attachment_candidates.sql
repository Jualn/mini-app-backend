ALTER TABLE media_upload_record
 MODIFY COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '0 pending;1 bound;2 cleaning;3 cleaned registration tombstone',
 DROP CHECK chk_media_upload_binding_owner,
 ADD CONSTRAINT chk_media_upload_binding_owner CHECK (bound_attachment_id IS NULL OR (status IN (0,1,2,3) AND bound_target_id IS NULL));
-- Existing records are trusted historical formal resources; new registrations explicitly opt into CANDIDATE.
ALTER TABLE media_attachment
 ADD COLUMN retention VARCHAR(16) NOT NULL DEFAULT 'FORMAL' COMMENT 'Independent retention: CANDIDATE or FORMAL',
 ADD COLUMN expires_at DATETIME(6) NULL COMMENT 'Shanghai candidate deadline; never extended by retries',
 ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Independent deletion tombstone',
 ADD CONSTRAINT chk_attachment_retention CHECK ((retention='FORMAL' AND expires_at IS NULL) OR (retention='CANDIDATE' AND expires_at IS NOT NULL)),
 ADD INDEX idx_attachment_owner_retention (registered_by, retention, deleted, created_at, id);
CREATE TABLE media_attachment_registration (
 operator_id BIGINT UNSIGNED NOT NULL COMMENT 'Authenticated registration actor',
 request_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL COMMENT 'Stable registration intent key',
 fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SHA256 of ordered kind/name/url values',
 attachment_id BIGINT UNSIGNED NULL COMMENT 'Committed resource, retained after expiration/deletion',
 PRIMARY KEY (operator_id,request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable attachment registration identity and replay';
