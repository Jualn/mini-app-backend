CREATE TABLE `admin_operation_log` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `operator_id`  BIGINT UNSIGNED NOT NULL COMMENT '执行操作的管理员用户ID',
  `action`       VARCHAR(64)     NOT NULL COMMENT '稳定操作编码',
  `target_type`  VARCHAR(32)     NOT NULL COMMENT '操作目标类型',
  `target_id`    BIGINT UNSIGNED NOT NULL COMMENT '操作目标ID',
  `reason`       VARCHAR(255)    NOT NULL COMMENT '操作原因',
  `before_value` VARCHAR(255)    DEFAULT NULL COMMENT '变更前值',
  `after_value`  VARCHAR(255)    DEFAULT NULL COMMENT '变更后值',
  `trace_id`     VARCHAR(64)     DEFAULT NULL COMMENT '请求追踪ID',
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_admin_operation_target` (`target_type`, `target_id`, `created_at`),
  KEY `idx_admin_operation_operator` (`operator_id`, `created_at`)
) ENGINE=InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci
  COMMENT='管理员业务操作日志';
