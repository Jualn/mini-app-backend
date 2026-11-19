-- Expand only. No user source changes, personnel grants, or capability enablement.
-- Codes/versions/keys use NO PAD binary collation: case and trailing spaces are significant.
CREATE TABLE rbac_permission_definition (
  code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  description VARCHAR(500) NOT NULL,
  namespaces JSON NOT NULL,
  lifecycle VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  readiness VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  grantable BOOLEAN NOT NULL DEFAULT FALSE,
  unavailable_reason VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  catalog_digest CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  PRIMARY KEY(code),
  CONSTRAINT ck_rbac_permission_state CHECK (lifecycle IN ('REGISTERED','ENABLED','DISABLED') AND readiness IN ('NOT_READY','READY') AND grantable IN (0,1)),
  CONSTRAINT ck_rbac_permission_available CHECK (NOT grantable OR (lifecycle='ENABLED' AND readiness='READY' AND unavailable_reason IS NULL)),
  CONSTRAINT ck_rbac_permission_namespaces CHECK (JSON_TYPE(namespaces)='ARRAY' AND JSON_LENGTH(namespaces)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE rbac_role (
  code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  name VARCHAR(128) NOT NULL,
  namespaces JSON NOT NULL,
  lifecycle VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  readiness VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  grantable BOOLEAN NOT NULL DEFAULT FALSE,
  unavailable_reason VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  catalog_digest CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  PRIMARY KEY(code),
  CONSTRAINT ck_rbac_role_state CHECK (lifecycle IN ('REGISTERED','ENABLED','DISABLED') AND readiness IN ('NOT_READY','READY') AND grantable IN (0,1)),
  CONSTRAINT ck_rbac_role_available CHECK (NOT grantable OR (lifecycle='ENABLED' AND readiness='READY' AND unavailable_reason IS NULL)),
  CONSTRAINT ck_rbac_role_namespaces CHECK (JSON_TYPE(namespaces)='ARRAY' AND JSON_LENGTH(namespaces)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE rbac_role_permission (
  role_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  permission_code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  PRIMARY KEY(role_code,permission_code),
  CONSTRAINT fk_rbac_member_role FOREIGN KEY(role_code) REFERENCES rbac_role(code),
  CONSTRAINT fk_rbac_member_permission FOREIGN KEY(permission_code) REFERENCES rbac_permission_definition(code)
) ENGINE=InnoDB;

CREATE TABLE rbac_authorization_authority (
  user_id BIGINT UNSIGNED NOT NULL,
  source VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  version VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY(user_id),
  CONSTRAINT fk_rbac_authority_user FOREIGN KEY(user_id) REFERENCES user_profile(id),
  CONSTRAINT ck_rbac_source CHECK (source IN ('LEGACY','ASSIGNMENT'))
) ENGINE=InnoDB;

CREATE TABLE rbac_user_role_assignment (
  id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  role_code VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  namespace VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  scope_type VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  granted_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) DEFAULT NULL,
  revoked_at DATETIME(6) DEFAULT NULL,
  version VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  grant_change_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  revoke_change_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin DEFAULT NULL,
  -- A released/expired slot is retained as history, never physically deleted.
  current_slot TINYINT DEFAULT 1,
  PRIMARY KEY(id),
  UNIQUE KEY uk_rbac_current_slot(user_id,role_code,namespace,scope_type,current_slot),
  KEY idx_rbac_assignment_current(user_id,revoked_at,expires_at),
  CONSTRAINT fk_rbac_assignment_user FOREIGN KEY(user_id) REFERENCES user_profile(id),
  CONSTRAINT fk_rbac_assignment_role FOREIGN KEY(role_code) REFERENCES rbac_role(code),
  CONSTRAINT ck_rbac_scope CHECK (scope_type='GLOBAL'),
  CONSTRAINT ck_rbac_namespace CHECK (namespace IN ('AUTHORIZATION','USER','POST','ACTIVITY','PUBLIC_EVENT','OFFICIAL_CONTENT','ARTICLE','LEARNING_TASK','REPORT','NOTIFICATION','SYSTEM','IDENTITY','ORGANIZATION','MENTOR','POINTS')),
  CONSTRAINT ck_rbac_assignment_time CHECK (expires_at IS NULL OR expires_at>granted_at),
  CONSTRAINT ck_rbac_assignment_revoke CHECK ((revoked_at IS NULL AND revoke_change_id IS NULL) OR (revoked_at>=granted_at AND revoke_change_id IS NOT NULL AND current_slot IS NULL)),
  CONSTRAINT ck_rbac_assignment_slot CHECK (current_slot IS NULL OR current_slot=1),
  CONSTRAINT ck_rbac_root_scope CHECK (role_code<>'ROOT' OR (namespace='AUTHORIZATION' AND expires_at IS NULL))
) ENGINE=InnoDB;

CREATE TABLE rbac_authorization_change (
  id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  operation_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  actor_user_id BIGINT UNSIGNED NOT NULL,
  target_user_id BIGINT UNSIGNED NOT NULL,
  assignment_id CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  action VARCHAR(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  idempotency_key VARBINARY(128) NOT NULL,
  request_fingerprint CHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  reason VARCHAR(500) NOT NULL,
  snapshot_version SMALLINT UNSIGNED NOT NULL,
  before_snapshot JSON DEFAULT NULL,
  after_snapshot JSON NOT NULL,
  receipt JSON NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY(id),
  UNIQUE KEY uk_rbac_actor_key(actor_user_id,idempotency_key),
  UNIQUE KEY uk_rbac_operation(operation_id),
  KEY idx_rbac_change_time(occurred_at,id),
  KEY idx_rbac_change_target(target_user_id,occurred_at,id),
  CONSTRAINT fk_rbac_change_actor FOREIGN KEY(actor_user_id) REFERENCES user_profile(id),
  CONSTRAINT fk_rbac_change_target FOREIGN KEY(target_user_id) REFERENCES user_profile(id),
  CONSTRAINT fk_rbac_change_assignment FOREIGN KEY(assignment_id) REFERENCES rbac_user_role_assignment(id),
  CONSTRAINT ck_rbac_change_action CHECK (action IN ('GRANT','REVOKE')),
  CONSTRAINT ck_rbac_change_snapshot CHECK (snapshot_version=1 AND ((action='GRANT' AND before_snapshot IS NULL) OR (action='REVOKE' AND before_snapshot IS NOT NULL))),
  CONSTRAINT ck_rbac_change_key CHECK (OCTET_LENGTH(idempotency_key) BETWEEN 16 AND 128),
  CONSTRAINT ck_rbac_change_reason CHECK (CHAR_LENGTH(reason) BETWEEN 1 AND 500)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Controlled catalog SHA-256: b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4. Registration only; no capabilities enabled.
INSERT INTO rbac_permission_definition VALUES ('activity:edit','创建/编辑主体聚合 / 生成Activity草稿建议','["ACTIVITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('activity:lifecycle','取消/提前结束','["ACTIVITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('activity:publish','发布或改动公开表示','["ACTIVITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('activity:read','管理读取','["ACTIVITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('activity:registration:dispose','管理员作废报名','["ACTIVITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('activity:registration:export','导出报名','["ACTIVITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('activity:registration:read','管理查看报名','["ACTIVITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('activity:unpublish','下架','["ACTIVITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('article-category:manage','维护分类','["ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('article-category:read','分类管理读取','["ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('article-publisher:manage','维护发布主体展示','["ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('article-publisher:read','发布主体管理读取','["ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('article:edit','创建草稿 / 编辑资讯','["ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('article:publish','人工发布/重新发布','["ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('article:read','管理读取','["ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('article:unpublish','撤下资讯','["ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('async:repair','人工修复、重放、删除等高风险操作','["SYSTEM"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('async:retry','人工恢复已dead的既有任务','["SYSTEM"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('audit-log:read','按职责查询审计/授权历史','["AUTHORIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('authorization:assignment-manage','授予/调整/撤销普通角色assignment','["AUTHORIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('authorization:delegation-manage','配置可委托上限','["AUTHORIZATION"]','DISABLED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('authorization:read','读取系统权限目录/角色/授权记录','["AUTHORIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('authorization:role-manage','编辑并发布角色版本','["AUTHORIZATION"]','DISABLED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('authorization:root-manage','最高权限主体任免/保护','["AUTHORIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('broadcast:edit','创建/编辑广播草稿','["NOTIFICATION"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('broadcast:publish','发布/取消尚未执行的广播','["NOTIFICATION"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('comment:dispose','管理删除/下架/恢复','["POST","ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('content:manage','旧粗权限适配，映射见迁移节','["POST","ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','DISABLED','NOT_READY',FALSE,'LEGACY_ONLY','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('content:read','聚合读取管理内容','["POST","ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('identity:map-manage','维护政治身份映射','["IDENTITY"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('learning-submission:read','管理读取成果/提交历史 / 成果审核通过/驳回 / 管理员读取私有成果文件','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('learning-submission:review','成果审核通过/驳回','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('learning-task:assign','预览受众/读取预览及名单 / 首次发布与初始分配 / 追加接收对象','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('learning-task:edit','创建/编辑草稿','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('learning-task:lifecycle','调整截止/取消/关闭','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('learning-task:publish','首次发布与初始分配','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('learning-task:read','管理任务列表/详情 / 查询分配和统计','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('learning-task:revoke','撤销未参与分配 / 例外撤销已参与分配','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('learning-task:revoke-participated','例外撤销已参与分配','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('media:read','管理读取可复用媒体','["ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('media:write','管理上传/登记/绑定媒体','["ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('mentor:qualification-manage','确认/撤销/到期导师资格','["MENTOR"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('notice:read','只读通知计划','["NOTIFICATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('official-content:edit','创建/编辑草稿或候选正文','["OFFICIAL_CONTENT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('official-content:publish','发布或修改公开表示','["OFFICIAL_CONTENT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('official-content:read','管理读取','["OFFICIAL_CONTENT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('official-content:unpublish','撤下','["OFFICIAL_CONTENT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('organization:map-manage','维护组织目录与成员关系','["ORGANIZATION"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('points:adjust','发起人工调整','["POINTS"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('points:adjust-approve','批准人工调整','["POINTS"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('points:read','管理查看人员积分','["POINTS"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('points:review','审核积分来源事项','["POINTS"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('points:rule-manage','积分规则版本维护','["POINTS"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('post-dictionary:manage','维护字典','["POST"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('post-dictionary:read','管理读取字典','["POST"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('post:curate','置顶/精选及取消','["POST"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('post:dispose','管理删除/下架','["POST"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('post:restore','恢复已删除/下架 Post','["POST"]','DISABLED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('public-event:edit','创建/编辑主体聚合 / 生成PublicEvent草稿建议','["PUBLIC_EVENT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('public-event:lifecycle','取消/提前结束','["PUBLIC_EVENT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('public-event:publish','发布或改动公开表示','["PUBLIC_EVENT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('public-event:read','管理读取','["PUBLIC_EVENT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('public-event:unpublish','下架','["PUBLIC_EVENT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('report:handle','单条处理/聚合正常结案/违规结案','["REPORT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('report:read','管理查询举报/事项 / 单条处理/聚合正常结案/违规结案','["REPORT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('review:decide','人工审核通过/拒绝','["POST","ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('review:read','查看审核队列/详情 / 人工审核通过/拒绝','["POST","ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('system:configure','修改系统配置','["SYSTEM"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('system:read','只读平台监测','["SYSTEM"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('user:manage','禁言/封禁/恢复','["USER"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('user:read','管理查询/详情','["USER"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('user:role','替换旧单字段角色','["USER"]','DISABLED','NOT_READY',FALSE,'LEGACY_ONLY','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_permission_definition VALUES ('wechat-notification:configure','模板/渠道配置管理','["NOTIFICATION"]','REGISTERED','NOT_READY',FALSE,'DEFERRED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role VALUES ('ROOT','授权治理 ROOT','["AUTHORIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('ROOT','audit-log:read');
INSERT INTO rbac_role_permission VALUES ('ROOT','authorization:assignment-manage');
INSERT INTO rbac_role_permission VALUES ('ROOT','authorization:read');
INSERT INTO rbac_role_permission VALUES ('ROOT','authorization:root-manage');
INSERT INTO rbac_role VALUES ('USER_GOVERNOR','用户治理管理员','["USER"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('USER_GOVERNOR','user:manage');
INSERT INTO rbac_role_permission VALUES ('USER_GOVERNOR','user:read');
INSERT INTO rbac_role VALUES ('CONTENT_EDITOR','内容编辑','["POST","ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('CONTENT_EDITOR','article-category:read');
INSERT INTO rbac_role_permission VALUES ('CONTENT_EDITOR','article-publisher:read');
INSERT INTO rbac_role_permission VALUES ('CONTENT_EDITOR','article:edit');
INSERT INTO rbac_role_permission VALUES ('CONTENT_EDITOR','article:read');
INSERT INTO rbac_role_permission VALUES ('CONTENT_EDITOR','content:read');
INSERT INTO rbac_role_permission VALUES ('CONTENT_EDITOR','media:read');
INSERT INTO rbac_role_permission VALUES ('CONTENT_EDITOR','media:write');
INSERT INTO rbac_role_permission VALUES ('CONTENT_EDITOR','official-content:edit');
INSERT INTO rbac_role_permission VALUES ('CONTENT_EDITOR','official-content:read');
INSERT INTO rbac_role VALUES ('CONTENT_PUBLISHER','内容发布','["ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('CONTENT_PUBLISHER','article:publish');
INSERT INTO rbac_role_permission VALUES ('CONTENT_PUBLISHER','article:read');
INSERT INTO rbac_role_permission VALUES ('CONTENT_PUBLISHER','article:unpublish');
INSERT INTO rbac_role_permission VALUES ('CONTENT_PUBLISHER','media:read');
INSERT INTO rbac_role_permission VALUES ('CONTENT_PUBLISHER','official-content:publish');
INSERT INTO rbac_role_permission VALUES ('CONTENT_PUBLISHER','official-content:read');
INSERT INTO rbac_role_permission VALUES ('CONTENT_PUBLISHER','official-content:unpublish');
INSERT INTO rbac_role VALUES ('CONTENT_REVIEWER','内容审核','["POST","ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('CONTENT_REVIEWER','review:decide');
INSERT INTO rbac_role_permission VALUES ('CONTENT_REVIEWER','review:read');
INSERT INTO rbac_role VALUES ('CONTENT_OPERATOR','内容运营/字典','["POST","ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('CONTENT_OPERATOR','comment:dispose');
INSERT INTO rbac_role_permission VALUES ('CONTENT_OPERATOR','content:read');
INSERT INTO rbac_role_permission VALUES ('CONTENT_OPERATOR','post-dictionary:manage');
INSERT INTO rbac_role_permission VALUES ('CONTENT_OPERATOR','post-dictionary:read');
INSERT INTO rbac_role_permission VALUES ('CONTENT_OPERATOR','post:curate');
INSERT INTO rbac_role_permission VALUES ('CONTENT_OPERATOR','post:dispose');
INSERT INTO rbac_role VALUES ('EVENT_OPERATOR','活动及公共事项管理员','["ACTIVITY","PUBLIC_EVENT","OFFICIAL_CONTENT","ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','activity:edit');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','activity:lifecycle');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','activity:publish');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','activity:read');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','activity:unpublish');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','media:read');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','media:write');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','public-event:edit');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','public-event:lifecycle');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','public-event:publish');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','public-event:read');
INSERT INTO rbac_role_permission VALUES ('EVENT_OPERATOR','public-event:unpublish');
INSERT INTO rbac_role VALUES ('REGISTRATION_READER','报名资料查看','["ACTIVITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('REGISTRATION_READER','activity:registration:read');
INSERT INTO rbac_role VALUES ('REGISTRATION_EXPORTER','报名导出','["ACTIVITY"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('REGISTRATION_EXPORTER','activity:registration:export');
INSERT INTO rbac_role_permission VALUES ('REGISTRATION_EXPORTER','activity:registration:read');
INSERT INTO rbac_role VALUES ('LEARNING_MANAGER','学习任务管理员','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('LEARNING_MANAGER','learning-task:assign');
INSERT INTO rbac_role_permission VALUES ('LEARNING_MANAGER','learning-task:edit');
INSERT INTO rbac_role_permission VALUES ('LEARNING_MANAGER','learning-task:lifecycle');
INSERT INTO rbac_role_permission VALUES ('LEARNING_MANAGER','learning-task:publish');
INSERT INTO rbac_role_permission VALUES ('LEARNING_MANAGER','learning-task:read');
INSERT INTO rbac_role_permission VALUES ('LEARNING_MANAGER','learning-task:revoke');
INSERT INTO rbac_role VALUES ('LEARNING_REVIEWER','学习成果审核员','["LEARNING_TASK"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('LEARNING_REVIEWER','learning-submission:read');
INSERT INTO rbac_role_permission VALUES ('LEARNING_REVIEWER','learning-submission:review');
INSERT INTO rbac_role_permission VALUES ('LEARNING_REVIEWER','learning-task:read');
INSERT INTO rbac_role VALUES ('REPORT_HANDLER','举报处理员','["REPORT"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('REPORT_HANDLER','report:handle');
INSERT INTO rbac_role_permission VALUES ('REPORT_HANDLER','report:read');
INSERT INTO rbac_role VALUES ('NOTICE_MONITOR','通知/平台监测查看者','["NOTIFICATION","SYSTEM"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('NOTICE_MONITOR','notice:read');
INSERT INTO rbac_role_permission VALUES ('NOTICE_MONITOR','system:read');
INSERT INTO rbac_role VALUES ('IDENTITY_ORG_STEWARD','身份与组织映射管理员','["IDENTITY","ORGANIZATION"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('IDENTITY_ORG_STEWARD','identity:map-manage');
INSERT INTO rbac_role_permission VALUES ('IDENTITY_ORG_STEWARD','organization:map-manage');
INSERT INTO rbac_role VALUES ('MENTOR_REGISTRAR','导师资格管理员','["MENTOR"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('MENTOR_REGISTRAR','mentor:qualification-manage');
INSERT INTO rbac_role VALUES ('POINTS_MANAGER','积分规则管理员','["POINTS"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('POINTS_MANAGER','points:read');
INSERT INTO rbac_role_permission VALUES ('POINTS_MANAGER','points:rule-manage');
INSERT INTO rbac_role VALUES ('POINTS_REVIEWER','积分事项审核员','["POINTS"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('POINTS_REVIEWER','points:read');
INSERT INTO rbac_role_permission VALUES ('POINTS_REVIEWER','points:review');
INSERT INTO rbac_role VALUES ('RECOVERY_OPERATOR','异步恢复操作员','["SYSTEM"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','b739af4238fad577f6935e154528b40f81cc83971cdd9e67507867abd9190bb4');
INSERT INTO rbac_role_permission VALUES ('RECOVERY_OPERATOR','async:retry');
INSERT INTO rbac_role_permission VALUES ('RECOVERY_OPERATOR','system:read');
