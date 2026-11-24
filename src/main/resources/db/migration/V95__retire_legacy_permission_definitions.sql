-- Retire the obsolete or unimplemented entries without rewriting authorization history.
CREATE TEMPORARY TABLE rbac_v95_catalog_guard (
    valid BOOLEAN NOT NULL,
    CHECK (valid = TRUE)
);
CREATE TEMPORARY TABLE rbac_v95_retired_permission (
    code VARCHAR(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY
);
INSERT INTO rbac_v95_retired_permission (code) VALUES
    ('authorization:delegation-manage'), ('authorization:role-manage'),
    ('content:manage'), ('user:role'), ('post:restore'), ('article-publisher:manage'),
    ('points:rule-manage'), ('broadcast:edit'), ('broadcast:publish'),
    ('system:configure'), ('wechat-notification:configure'), ('async:retry'), ('async:repair');

START TRANSACTION;

SELECT code FROM rbac_role WHERE code = 'ROOT' FOR UPDATE;

INSERT INTO rbac_v95_catalog_guard (valid)
SELECT
    (SELECT COUNT(*) FROM rbac_permission_definition p
     JOIN rbac_v95_retired_permission retired ON retired.code = p.code) = 13;

INSERT INTO rbac_v95_catalog_guard (valid)
SELECT
    EXISTS (
        SELECT 1 FROM rbac_role WHERE code = 'RECOVERY_OPERATOR'
          AND lifecycle = 'REGISTERED' AND readiness = 'NOT_READY' AND grantable = 0
    )
    AND NOT EXISTS (
        SELECT 1 FROM rbac_user_role_assignment WHERE role_code = 'RECOVERY_OPERATOR'
    )
    AND NOT EXISTS (
        SELECT 1 FROM rbac_role_permission m
        JOIN rbac_v95_retired_permission retired ON retired.code = m.permission_code
        WHERE m.role_code <> 'RECOVERY_OPERATOR'
    )
    AND NOT EXISTS (
        SELECT 1 FROM rbac_permission_definition
        WHERE catalog_digest <> 'db10ee1aeaba05049326a5e0e266bf5b4fa615510e2aaeb6506ff4d68fc860db'
    )
    AND NOT EXISTS (
        SELECT 1 FROM rbac_role
        WHERE catalog_digest <> 'db10ee1aeaba05049326a5e0e266bf5b4fa615510e2aaeb6506ff4d68fc860db'
    );

DELETE FROM rbac_role_permission WHERE role_code = 'RECOVERY_OPERATOR';
DELETE FROM rbac_role WHERE code = 'RECOVERY_OPERATOR';
DELETE p FROM rbac_permission_definition p
JOIN rbac_v95_retired_permission retired ON retired.code = p.code;

UPDATE rbac_permission_definition
SET unavailable_reason = 'DEFERRED'
WHERE code = 'learning-task:revoke-participated'
  AND lifecycle = 'REGISTERED' AND readiness = 'NOT_READY' AND grantable = 0;

UPDATE rbac_permission_definition
SET catalog_digest = '9c44001b691483525a50f1be42f4646ed97873c7ff259bc4c44a36343a6dee4d';
UPDATE rbac_role
SET catalog_digest = '9c44001b691483525a50f1be42f4646ed97873c7ff259bc4c44a36343a6dee4d';

COMMIT;

DROP TEMPORARY TABLE rbac_v95_retired_permission;
DROP TEMPORARY TABLE rbac_v95_catalog_guard;
