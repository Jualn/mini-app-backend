-- unified-v1-r1 expand only. Preserve all existing availability and authorization facts.
-- Apply before the matching catalog binary; no old/new catalog binaries may be mixed.
INSERT INTO rbac_role(code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest)
VALUES('POINTS_READER_V1','积分受控读取员','["POINTS"]','REGISTERED','NOT_READY',FALSE,
 'PROVIDER_NOT_VERIFIED','9274dd63c35cae48e78e946e142ec680f850ecc6736d5185c660326061e8fa66');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('POINTS_READER_V1','points:read');
UPDATE rbac_permission_definition SET catalog_digest='9274dd63c35cae48e78e946e142ec680f850ecc6736d5185c660326061e8fa66'
WHERE catalog_digest='db7135c49a66b9da05b1fab728d266a7bf0fd64492e0bac11c21aa780cc8f981';
UPDATE rbac_role SET catalog_digest='9274dd63c35cae48e78e946e142ec680f850ecc6736d5185c660326061e8fa66'
WHERE catalog_digest='db7135c49a66b9da05b1fab728d266a7bf0fd64492e0bac11c21aa780cc8f981';
