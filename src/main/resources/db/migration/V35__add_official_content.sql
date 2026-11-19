-- Additive M01 storage. No updates, drops or reclassification of existing business data.
CREATE TABLE official_content (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    title VARCHAR(120) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    body MEDIUMTEXT NOT NULL,
    publisher VARCHAR(120) NOT NULL,
    category VARCHAR(32) NOT NULL,
    publish_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    video_attachment_id BIGINT UNSIGNED NULL,
    created_by BIGINT UNSIGNED NOT NULL,
    updated_by BIGINT UNSIGNED NOT NULL,
    revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
    published_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_official_public (publish_status, id),
    KEY idx_official_category (category, publish_status, id),
    CONSTRAINT chk_official_category CHECK (category IN ('NEWS','BRANCH','NOTICE','E_TOGETHER','INFO_RED','E_SAFETY','EXEMPLARY','ACHIEVEMENT')),
    CONSTRAINT chk_official_status CHECK (publish_status IN ('DRAFT','PUBLISHED','UNPUBLISHED')),
    CONSTRAINT chk_official_published CHECK (publish_status <> 'PUBLISHED' OR published_at IS NOT NULL),
    CONSTRAINT fk_official_video FOREIGN KEY (video_attachment_id) REFERENCES media_attachment(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Expand the existing reference constraint while preserving all old links.
ALTER TABLE event_attachment_link
    DROP CHECK chk_event_attachment_target_type,
    ADD CONSTRAINT chk_event_attachment_target_type CHECK (target_type IN (2, 3, 7));
