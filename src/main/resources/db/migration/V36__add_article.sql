-- Article is independent of Post and OfficialContent. Existing data is unchanged.
-- AuditScene is a separate namespace: check new scene 9, not legitimate existing scene 8.
CREATE TABLE article_v36_target_preflight(valid TINYINT NOT NULL, CONSTRAINT chk_article_v36_target_free CHECK(valid=1));
INSERT INTO article_v36_target_preflight
 SELECT IF((SELECT COUNT(*) FROM comment WHERE target_type=8)
  +(SELECT COUNT(*) FROM like_record WHERE target_type=8)
  +(SELECT COUNT(*) FROM share_record WHERE target_type=8)
  +(SELECT COUNT(*) FROM view_log WHERE target_type=8)
  +(SELECT COUNT(*) FROM media_attachment WHERE target_type=8)
  +(SELECT COUNT(*) FROM media_upload_record WHERE target_type=8)
  +(SELECT COUNT(*) FROM event_attachment_link WHERE target_type=8)
  +(SELECT COUNT(*) FROM notification WHERE target_type=8)
  +(SELECT COUNT(*) FROM search_doc WHERE target_type=8)
  +(SELECT COUNT(*) FROM report WHERE target_type=8)
  +(SELECT COUNT(*) FROM timeline WHERE target_type=8)
  +(SELECT COUNT(*) FROM content_audit_log WHERE target_type=9)=0,1,0);
DROP TABLE article_v36_target_preflight;
CREATE TABLE article_publisher (
 id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
 display_name VARCHAR(120) NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 updated_by BIGINT UNSIGNED NOT NULL,
 updated_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE article_category (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(60) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
 display_order INT NOT NULL,
 enabled BOOLEAN NOT NULL,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 created_by BIGINT UNSIGNED NOT NULL,
 updated_by BIGINT UNSIGNED NOT NULL,
 UNIQUE KEY uk_article_category_name(name),
 CONSTRAINT chk_article_category_order CHECK(display_order BETWEEN 0 AND 100000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE article (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 title VARCHAR(120) NOT NULL,
 summary VARCHAR(500) NOT NULL,
 body JSON NOT NULL,
 plain_text MEDIUMTEXT NOT NULL,
 category_id BIGINT UNSIGNED NULL,
 publisher_id BIGINT UNSIGNED NOT NULL,
 cover_attachment_id BIGINT UNSIGNED NULL,
 comments_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 publish_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
 created_by BIGINT UNSIGNED NOT NULL,
 updated_by BIGINT UNSIGNED NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 published_at DATETIME(6) NULL,
 last_published_at DATETIME(6) NULL,
 view_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
 like_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
 comment_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
 share_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
 KEY idx_article_public(publish_status,published_at,id),
 KEY idx_article_category(category_id,publish_status,published_at,id),
 KEY idx_article_admin(updated_at,id),
 CONSTRAINT fk_article_category FOREIGN KEY(category_id) REFERENCES article_category(id),
 CONSTRAINT fk_article_publisher FOREIGN KEY(publisher_id) REFERENCES article_publisher(id),
 CONSTRAINT fk_article_cover FOREIGN KEY(cover_attachment_id) REFERENCES media_attachment(id),
 CONSTRAINT chk_article_status CHECK(publish_status IN ('DRAFT','PUBLISHED','UNPUBLISHED')),
 CONSTRAINT chk_article_times CHECK((publish_status='DRAFT' AND published_at IS NULL AND last_published_at IS NULL) OR (publish_status<>'DRAFT' AND published_at IS NOT NULL AND last_published_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE event_attachment_link DROP CHECK chk_event_attachment_target_type,
 ADD CONSTRAINT chk_event_attachment_target_type CHECK(target_type IN(2,3,7,8));
ALTER TABLE media_attachment DROP CHECK chk_media_attachment_kind,
 ADD CONSTRAINT chk_media_attachment_kind CHECK(kind IN('IMAGE','POSTER','QR_CODE','PDF','WORD','LINK','VIDEO')),
 ADD content_type VARCHAR(100) NULL,
 ADD size_bytes BIGINT UNSIGNED NULL,
 ADD width INT UNSIGNED NULL,
 ADD height INT UNSIGNED NULL,
 ADD duration_seconds DOUBLE NULL,
 ADD verified BOOLEAN NOT NULL DEFAULT FALSE;
-- Nullable day leaves all historical view records unchanged, including duplicates.
ALTER TABLE view_log ADD view_day DATE NULL,
 ADD UNIQUE KEY uk_view_target_user_day(target_type,target_id,user_id,view_day);
ALTER TABLE comment ADD reply_to_comment_id BIGINT UNSIGNED NULL;
ALTER TABLE media_upload_record ADD declared_kind VARCHAR(16) NULL,
 ADD declared_content_type VARCHAR(100) NULL, ADD declared_size_bytes BIGINT UNSIGNED NULL;
