-- Additive Post organization. No historical content, attachment, interaction or author rewrite.
-- Audit scene 10 is candidate-only and cannot publish an effective Post from a callback.
CREATE TABLE post_v40_audit_preflight (
 valid TINYINT NOT NULL, CONSTRAINT chk_post_v40_scene_free CHECK(valid=1)
);
INSERT INTO post_v40_audit_preflight SELECT IF((SELECT COUNT(*) FROM content_audit_log WHERE target_type=10)=0,1,0);
DROP TABLE post_v40_audit_preflight;

CREATE TABLE post_category (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(64) NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
 display_order INT NOT NULL DEFAULT 0,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
 UNIQUE KEY uk_post_category_code(code),
 CONSTRAINT chk_post_category_state CHECK(status IN ('ACTIVE','INACTIVE')),
 CONSTRAINT chk_post_category_order CHECK(display_order>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE post_category_type (
 category_id BIGINT UNSIGNED NOT NULL,
 post_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(category_id,post_type),
 CONSTRAINT fk_post_category_type_category FOREIGN KEY(category_id) REFERENCES post_category(id)
) ENGINE=InnoDB;
CREATE TABLE topic (
 id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(64) NOT NULL,
 status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
 display_order INT NOT NULL DEFAULT 0,
 revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
 UNIQUE KEY uk_topic_code(code),
 CONSTRAINT chk_topic_state CHECK(status IN ('ACTIVE','INACTIVE')),
 CONSTRAINT chk_topic_order CHECK(display_order>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE post
 ADD COLUMN post_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NORMAL',
 ADD COLUMN category_id BIGINT UNSIGNED NULL,
 ADD COLUMN revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
 ADD COLUMN audit_revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
 ADD KEY idx_post_type_status_id(post_type,status,id),
 ADD KEY idx_post_category_type_status_id(category_id,post_type,status,id),
 ADD CONSTRAINT fk_post_category_type FOREIGN KEY(category_id,post_type) REFERENCES post_category_type(category_id,post_type),
 ADD CONSTRAINT chk_post_qa_category CHECK(post_type<>'MENTOR_QA' OR category_id IS NOT NULL);
CREATE TABLE post_topic (
 post_id BIGINT UNSIGNED NOT NULL,
 topic_id BIGINT UNSIGNED NOT NULL,
 position TINYINT UNSIGNED NOT NULL,
 PRIMARY KEY(post_id,topic_id),
 UNIQUE KEY uk_post_topic_position(post_id,position),
 KEY idx_topic_post(topic_id,post_id),
 CONSTRAINT fk_post_topic_post FOREIGN KEY(post_id) REFERENCES post(id),
 CONSTRAINT fk_post_topic_topic FOREIGN KEY(topic_id) REFERENCES topic(id),
 CONSTRAINT chk_post_topic_position CHECK(position<=2)
) ENGINE=InnoDB;
-- reply_to_comment_id already exists from V36. Preserve all legacy reply_to_uid values.
ALTER TABLE comment ADD COLUMN mentor_evidence VARCHAR(512) NULL;

INSERT INTO post_category(code,name,status,display_order) VALUES
 ('ACADEMIC','学业困扰','ACTIVE',0),
 ('COMPETITION','竞赛相关','ACTIVE',1),
 ('EMPLOYMENT','就业相关','ACTIVE',2),
 ('SOCIAL_PRACTICE','社会实践相关','ACTIVE',3);
INSERT INTO post_category_type(category_id,post_type)
 SELECT id,'MENTOR_QA' FROM post_category;
