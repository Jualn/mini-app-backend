ALTER TABLE `content_audit_log`
  ADD COLUMN `idempotency_key` VARCHAR(64) DEFAULT NULL
    COMMENT '管理员写命令幂等键，仅人工审核使用' AFTER `admin_remark`,
  ADD UNIQUE KEY `uk_audit_idempotency_key` (`idempotency_key`);
