ALTER TABLE `user_profile`
  ADD COLUMN `avatar_object_key` VARCHAR(512) DEFAULT NULL
    COMMENT '头像COS对象键' AFTER `avatar_url`,
  ADD COLUMN `background_object_key` VARCHAR(512) DEFAULT NULL
    COMMENT '背景图COS对象键' AFTER `background_url`;

ALTER TABLE `media_attachment`
  ADD COLUMN `object_key` VARCHAR(512) DEFAULT NULL
    COMMENT 'COS对象键；外链类型为空' AFTER `type`;

ALTER TABLE `comment`
  ADD COLUMN `image_object_key` VARCHAR(512) DEFAULT NULL
    COMMENT '评论配图COS对象键' AFTER `image_url`;

CREATE TABLE `media_upload_record` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `object_key`      VARCHAR(512)     NOT NULL COMMENT 'COS对象键',
  `user_id`         BIGINT UNSIGNED  NOT NULL COMMENT '申请上传的小程序用户ID',
  `target_type`     TINYINT          NOT NULL COMMENT '计划绑定的业务类型',
  `status`          TINYINT          NOT NULL DEFAULT 0 COMMENT '0-PENDING 1-BOUND 2-CLEANING',
  `bound_target_id` BIGINT UNSIGNED  DEFAULT NULL COMMENT '绑定后的业务目标ID',
  `cleanup_after`   DATETIME         NOT NULL COMMENT '允许清理的最早时间',
  `retry_count`     INT UNSIGNED     NOT NULL DEFAULT 0,
  `last_error`      VARCHAR(512)     DEFAULT NULL,
  `created_at`      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_media_upload_object_key` (`object_key`),
  KEY `idx_media_upload_cleanup` (`status`, `cleanup_after`),
  KEY `idx_media_upload_user_type` (`user_id`, `target_type`)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci
  COMMENT='COS上传临时记录与绑定状态';
