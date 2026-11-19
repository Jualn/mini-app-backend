CREATE TABLE comment_management_history (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Stable comment management effect identity',
 comment_id BIGINT NOT NULL COMMENT 'Comment owner resource identity',
 operator_id BIGINT NOT NULL COMMENT 'Authorized management account',
 action VARCHAR(24) NOT NULL COMMENT 'TAKE_DOWN or RESTORE',
 reason VARCHAR(255) NOT NULL COMMENT 'Required management reason',
 created_at DATETIME(6) NOT NULL COMMENT 'Effect time Asia/Shanghai',
 PRIMARY KEY(id),KEY idx_comment_management(comment_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Proven comment management effects; no guessed legacy deletion sources';
