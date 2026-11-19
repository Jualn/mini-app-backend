-- Publish the scoped party personnel work duty.
START TRANSACTION;

UPDATE rbac_permission_definition SET catalog_digest = '2e3736b9aa1c6d72e9f4aaea4a28af0318710dfcd7b7b989799070d26040b107'
WHERE catalog_digest = '9c44001b691483525a50f1be42f4646ed97873c7ff259bc4c44a36343a6dee4d';
UPDATE rbac_role SET catalog_digest = '2e3736b9aa1c6d72e9f4aaea4a28af0318710dfcd7b7b989799070d26040b107'
WHERE catalog_digest = '9c44001b691483525a50f1be42f4646ed97873c7ff259bc4c44a36343a6dee4d';

INSERT INTO rbac_permission_definition
    (code, description, namespaces, lifecycle, readiness, grantable, unavailable_reason, catalog_digest)
VALUES ('party-personnel:manage', '在授权党组织内维护党建人员、导入及名单完整性',
    '["ORGANIZATION"]', 'ENABLED', 'READY', 1, NULL, '2e3736b9aa1c6d72e9f4aaea4a28af0318710dfcd7b7b989799070d26040b107');

INSERT INTO rbac_role
    (code, name, namespaces, lifecycle, readiness, grantable, unavailable_reason, catalog_digest)
VALUES ('PARTY_ORG_PERSONNEL_MANAGER_V1', '党建组织人员管理员',
    '["ORGANIZATION"]', 'ENABLED', 'READY', 1, NULL, '2e3736b9aa1c6d72e9f4aaea4a28af0318710dfcd7b7b989799070d26040b107');

INSERT INTO rbac_role_permission (role_code, permission_code)
VALUES ('PARTY_ORG_PERSONNEL_MANAGER_V1', 'party-personnel:manage');

COMMIT;
