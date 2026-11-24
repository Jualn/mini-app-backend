-- Dedicated automatic candidate scene 11; never create human review decisions for it.
CREATE TEMPORARY TABLE unified_r3_audit_preflight (ok TINYINT NOT NULL CHECK(ok=1));
INSERT INTO unified_r3_audit_preflight SELECT IF((SELECT COUNT(*) FROM content_audit_log WHERE target_type=11)=0,1,0);
DROP TEMPORARY TABLE unified_r3_audit_preflight;
-- unified-v1-r3: catalog data only; preserve all existing availability, assignments and audit.
-- Stop mixed catalog binaries before applying. New definitions remain NOT_READY / not grantable.
INSERT INTO rbac_role(code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('ARTICLE_CATEGORY_STEWARD_V1','文章栏目与分类维护','["ARTICLE"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('ARTICLE_CATEGORY_STEWARD_V1','article-category:manage');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('ARTICLE_CATEGORY_STEWARD_V1','article-category:read');
INSERT INTO rbac_role(code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('POST_DICTIONARY_STEWARD_V1','帖子分类与话题维护','["POST"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('POST_DICTIONARY_STEWARD_V1','post-dictionary:manage');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('POST_DICTIONARY_STEWARD_V1','post-dictionary:read');
INSERT INTO rbac_role(code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('POST_MODERATOR_V1','帖子与评论管理','["POST"]','REGISTERED','NOT_READY',FALSE,'PROVIDER_NOT_VERIFIED','ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('POST_MODERATOR_V1','comment:dispose');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('POST_MODERATOR_V1','content:read');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('POST_MODERATOR_V1','post:curate');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('POST_MODERATOR_V1','post:dispose');
UPDATE rbac_permission_definition SET catalog_digest='ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424' WHERE catalog_digest='9274dd63c35cae48e78e946e142ec680f850ecc6736d5185c660326061e8fa66';
UPDATE rbac_role SET catalog_digest='ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424' WHERE catalog_digest='9274dd63c35cae48e78e946e142ec680f850ecc6736d5185c660326061e8fa66';
