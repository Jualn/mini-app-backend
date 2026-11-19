-- Article content V1: no data copying, organization grants or scene inference.
ALTER TABLE article
 ADD COLUMN attachment_ids JSON NULL COMMENT 'Ordered independent PDF/WORD Attachment IDs; NULL means pre-extension empty list',
 ADD COLUMN source JSON NULL COMMENT 'Optional declared source name and safe HTTPS URL',
 ADD COLUMN deleted_at DATETIME(6) NULL COMMENT 'Logical deletion time in Asia/Shanghai; retains publish status and all references',
 ADD COLUMN deleted_by BIGINT UNSIGNED NULL COMMENT 'ADMIN operator responsible for logical deletion',
 ADD CONSTRAINT chk_article_deleted_pair CHECK ((deleted_at IS NULL AND deleted_by IS NULL) OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL AND publish_status <> 'PUBLISHED')),
 ADD KEY idx_article_deleted_public(deleted_at,publish_status,published_at,id);
ALTER TABLE article_category ADD COLUMN scene_codes JSON NULL COMMENT 'Top-level scene mappings; NULL is an unconfigured empty set, never inferred from name';
CREATE TABLE article_favorite (
 user_id BIGINT UNSIGNED NOT NULL COMMENT 'Login User owner, not Person or administrator',
 article_id BIGINT UNSIGNED NOT NULL COMMENT 'Article relationship identity retained when target becomes unavailable',
 favorited_at DATETIME(6) NOT NULL COMMENT 'First current favorite time in Asia/Shanghai; duplicate PUT preserves this value',
 PRIMARY KEY(user_id,article_id),
 KEY idx_article_favorite_page(user_id,favorited_at,article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Current per-User Article favorites, independent of Post interactions';
-- Deliberately no cascading foreign keys: cancellation remains possible for an absent target.
CREATE INDEX idx_learning_task_article ON learning_task(article_id,id);
