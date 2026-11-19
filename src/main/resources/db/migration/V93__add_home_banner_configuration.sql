-- Register the matching runtime catalog without publishing grants or capability readiness.
ALTER TABLE rbac_user_role_assignment DROP CHECK ck_rbac_namespace,
    ADD CONSTRAINT ck_rbac_namespace CHECK (namespace IN (
        'AUTHORIZATION','USER','POST','ACTIVITY','PUBLIC_EVENT','OFFICIAL_CONTENT','ARTICLE',
        'LEARNING_TASK','REPORT','NOTIFICATION','SYSTEM','IDENTITY','ORGANIZATION','MENTOR','POINTS','HOME'
    ));

CREATE TABLE home_banner_configuration (
    id TINYINT UNSIGNED NOT NULL PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL CHECK (revision >= 0)
) ENGINE=InnoDB;
INSERT INTO home_banner_configuration (id, revision) VALUES (1, 0);

CREATE TABLE home_banner (
    position TINYINT UNSIGNED NOT NULL PRIMARY KEY CHECK (position BETWEEN 0 AND 2),
    target_type ENUM('ARTICLE','ACTIVITY') NOT NULL,
    target_id BIGINT UNSIGNED NOT NULL,
    enabled BOOLEAN NOT NULL,
    title_override VARCHAR(200) NULL,
    cover_attachment_id BIGINT UNSIGNED NULL,
    UNIQUE KEY uk_home_banner_target (target_type, target_id),
    KEY idx_home_banner_cover (cover_attachment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

START TRANSACTION;
UPDATE rbac_permission_definition SET catalog_digest='db10ee1aeaba05049326a5e0e266bf5b4fa615510e2aaeb6506ff4d68fc860db' WHERE catalog_digest='01fc24663932eeb839b54ce107b6c1f1f5619bf1980627099a329b3780fc9392';
UPDATE rbac_role SET catalog_digest='db10ee1aeaba05049326a5e0e266bf5b4fa615510e2aaeb6506ff4d68fc860db' WHERE catalog_digest='01fc24663932eeb839b54ce107b6c1f1f5619bf1980627099a329b3780fc9392';
INSERT INTO rbac_permission_definition(code,description,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest)
VALUES ('home-banner:manage','读取及保存平台首页精选配置，并查询可关联的公开内容选项','["HOME"]','REGISTERED','NOT_READY',0,'PROVIDER_NOT_VERIFIED','db10ee1aeaba05049326a5e0e266bf5b4fa615510e2aaeb6506ff4d68fc860db');
INSERT INTO rbac_role(code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest)
VALUES ('HOME_BANNER_MANAGER_V1','首页精选管理员','["HOME"]','REGISTERED','NOT_READY',0,'PROVIDER_NOT_VERIFIED','db10ee1aeaba05049326a5e0e266bf5b4fa615510e2aaeb6506ff4d68fc860db');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES ('HOME_BANNER_MANAGER_V1','home-banner:manage');
COMMIT;
