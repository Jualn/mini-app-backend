-- Points r6: append immutable correction pairs; never rewrite original review/ledger facts.
-- Stop old Points writers and publish matching catalogs with this provider. No assignments are seeded.
ALTER TABLE point_application DROP CHECK chk_point_app_review,
 ADD CONSTRAINT chk_point_app_review CHECK(
  (status IN ('PENDING','CLOSED') AND reviewer_user_id IS NULL AND review_note IS NULL AND reviewed_at IS NULL AND awarded_points IS NULL AND grant_ledger_id IS NULL)
  OR (status='REJECTED' AND reviewer_user_id IS NOT NULL AND reviewer_user_id<>applicant_user_id AND reviewed_at IS NOT NULL AND awarded_points IS NULL AND grant_ledger_id IS NULL)
  OR (status='APPROVED' AND reviewer_user_id IS NOT NULL AND reviewer_user_id<>applicant_user_id AND reviewed_at IS NOT NULL AND awarded_points IS NOT NULL AND awarded_points>0 AND grant_ledger_id IS NOT NULL));
ALTER TABLE point_ledger
 MODIFY kind VARCHAR(24) NOT NULL COMMENT 'GRANT original approval; CORRECTION_GRANT replacement; REVERSAL full cancellation',
 MODIFY reason VARCHAR(2000) NULL COMMENT 'Optional original review note; mandatory adjustment reason',
 ADD COLUMN basis VARCHAR(2000) NULL COMMENT 'Immutable evidence text supplied for correction or final reversal',
 ADD COLUMN correction_id CHAR(36) COLLATE utf8mb4_0900_bin NULL COMMENT 'Shared immutable identity of one atomic reversal and replacement pair',
 ADD KEY idx_point_original(original_ledger_id),
 DROP INDEX uk_point_reversal_original,
 ADD COLUMN reversal_target_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN kind='REVERSAL' THEN original_ledger_id ELSE NULL END) STORED COMMENT 'Each positive ledger node can be reversed only once',
 ADD UNIQUE KEY uk_point_reversal_target(reversal_target_id),
 ADD UNIQUE KEY uk_point_correction_kind(correction_id,kind),
 DROP CHECK chk_point_ledger_kind,
 ADD CONSTRAINT chk_point_ledger_kind CHECK(
  (kind='GRANT' AND points_delta>0 AND original_ledger_id IS NULL AND correction_id IS NULL AND basis IS NULL)
  OR (kind='CORRECTION_GRANT' AND points_delta BETWEEN 1 AND 10 AND original_ledger_id IS NOT NULL AND correction_id IS NOT NULL AND reason IS NOT NULL AND basis IS NOT NULL)
  OR (kind='REVERSAL' AND points_delta BETWEEN -2147483647 AND -1 AND original_ledger_id IS NOT NULL AND reason IS NOT NULL AND basis IS NOT NULL));
ALTER TABLE point_authorization_audit DROP CHECK ck_point_audit_operation,
 ADD CONSTRAINT ck_point_audit_operation CHECK(operation_name IN ('CREATE','REVIEW','REVERSE','CORRECT','CLOSE'));
CREATE TABLE point_notification_resolution (
 occurrence VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY COMMENT 'Immutable business notification occurrence, independent of retries',
 outcome VARCHAR(40) NOT NULL COMMENT 'INBOX_ACCEPTED or explicit recipient skip evidence',
 recorded_at DATETIME(6) NOT NULL COMMENT 'UTC worker transaction time'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable local Points notification outcome; no private content';
UPDATE rbac_permission_definition SET catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748' WHERE catalog_digest='620bcf59e0b93c40460bc503cbdebfb426c6326acdc929bc4448329928b3409e';
UPDATE rbac_permission_definition SET description='对当前有效积分授予发起完整撤销或原子改分，不提供任意加分',lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='points:adjust' AND catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
UPDATE rbac_permission_definition SET description='在同一单管理员操作内确认积分撤销或改分；与points:adjust同时具备，不要求第二人审批',lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='points:adjust-approve' AND catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
UPDATE rbac_permission_definition SET description='管理查看人员积分',lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='points:read' AND catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
UPDATE rbac_permission_definition SET description='审核积分来源事项',lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='points:review' AND catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
UPDATE rbac_permission_definition SET description='置顶/精选及取消；Post（NORMAL/MENTOR_QA）分类归属维护，不含字典维护或用户原文改写' WHERE code='post:curate' AND catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
UPDATE rbac_permission_definition SET description='管理员下架及安全恢复NORMAL/MENTOR_QA；恢复须来源可证明、未作者删除且当前安全通过，不含作者终止删除' WHERE code='post:dispose' AND catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
UPDATE rbac_role SET catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748' WHERE catalog_digest='620bcf59e0b93c40460bc503cbdebfb426c6326acdc929bc4448329928b3409e';
UPDATE rbac_role SET name='积分纠错管理员',lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='POINTS_MANAGER' AND catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
DELETE FROM rbac_role_permission WHERE role_code='POINTS_MANAGER' AND permission_code='points:rule-manage';
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('POINTS_MANAGER','points:adjust');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('POINTS_MANAGER','points:adjust-approve');
UPDATE rbac_role SET name='积分事项审核员',lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='POINTS_REVIEWER' AND catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
UPDATE rbac_role SET name='积分受控读取员',lifecycle='ENABLED',readiness='READY',grantable=1,unavailable_reason=NULL WHERE code='POINTS_READER_V1' AND catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
