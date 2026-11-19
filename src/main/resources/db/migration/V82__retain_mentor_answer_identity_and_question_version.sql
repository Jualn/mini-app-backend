-- Unknown historical evidence is deliberately not reconstructed from current bindings.
ALTER TABLE post
 ADD COLUMN question_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'Opaque QA content submission identity; category/topics never change it',
 ADD COLUMN question_content_locked BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Permanent protection after the first proven formal answer is published';
ALTER TABLE comment
 ADD COLUMN question_version VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'QA version fixed at acceptance',
 ADD COLUMN mentor_attribution JSON NULL COMMENT 'Immutable original account/person/binding/qualification and profile evidence',
 ADD COLUMN first_published_at DATETIME(6) NULL COMMENT 'First legitimate public answer time Asia/Shanghai; never reset on restoration';
CREATE INDEX idx_mentor_answer_version ON comment(target_type,target_id,parent_id,question_version,status);

ALTER TABLE comment ADD COLUMN submission_notice VARCHAR(32) NULL COMMENT 'Author-safe candidate outcome; QUESTION_CHANGED never migrates an answer to another version';
