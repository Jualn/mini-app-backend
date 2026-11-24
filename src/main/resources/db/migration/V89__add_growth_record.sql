CREATE TABLE post_growth_record (
    post_id BIGINT NOT NULL COMMENT 'Post identity; unique permanent growth association',
    person_id VARCHAR(64) NOT NULL COMMENT 'Stable Person at acceptance; never inferred from current author binding',
    organization_id VARCHAR(64) NOT NULL COMMENT 'Exact publishing PARTY_BRANCH; immutable across membership changes',
    record_type VARCHAR(32) NOT NULL COMMENT 'GROWTH or POLITICAL_BIRTHDAY; immutable',
    PRIMARY KEY (post_id),
    KEY idx_growth_person (person_id,post_id),
    KEY idx_growth_organization (organization_id,record_type,post_id),
    CONSTRAINT ck_growth_record_type CHECK (record_type IN ('GROWTH','POLITICAL_BIRTHDAY'))
) ENGINE=InnoDB COMMENT='Post owner growth association; no independent growth document or anniversary facts';

CREATE TABLE growth_media_context (
    upload_id VARCHAR(64) NOT NULL COMMENT 'Media allocation identity',
    file_index INT NOT NULL COMMENT 'Zero-based position within allocation',
    owner_user_id BIGINT NOT NULL COMMENT 'Original uploading User',
    person_id VARCHAR(64) NOT NULL COMMENT 'Trusted current Person at allocation',
    organization_id VARCHAR(64) NOT NULL COMMENT 'Exact publishing organization',
    record_type VARCHAR(32) NOT NULL COMMENT 'Fixed GROWTH or POLITICAL_BIRTHDAY context',
    editing_post_id BIGINT NULL COMMENT 'Only this existing Post may take over an edit candidate',
    source_key VARCHAR(512) NOT NULL COMMENT 'Private immutable allocation source object',
    media_id BIGINT NULL COMMENT 'Verified media_upload_record identity after registration',
    original_name VARCHAR(255) NOT NULL COMMENT 'Declared original display name',
    content_type VARCHAR(64) NOT NULL COMMENT 'Declared MIME, checked against actual bytes',
    size_bytes BIGINT NOT NULL COMMENT 'Declared byte size, checked against actual bytes',
    bound_post_id BIGINT NULL COMMENT 'Permanent formal owner; retained after removal or author deletion',
    created_at DATETIME(6) NOT NULL COMMENT 'Beijing allocation time',
    expires_at DATETIME(6) NOT NULL COMMENT 'Beijing candidate acceptance deadline',
    PRIMARY KEY(upload_id,file_index),
    UNIQUE KEY uk_growth_media_id(media_id),
    UNIQUE KEY uk_growth_media_source(source_key)
) ENGINE=InnoDB COMMENT='Media owner growth upload context and permanent historical reference; bytes use existing private snapshot cleanup';

ALTER TABLE content_safety_review ADD COLUMN growth_organization_id VARCHAR(64) NULL COMMENT 'Fixed growth organization at review reservation; NULL only for ordinary PLATFORM subjects';
