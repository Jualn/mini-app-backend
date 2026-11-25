-- Read-only. Execute after V41 on the explicitly approved target primary database.
-- Coverage includes deleted users: no silently uninitialized identity on restoration.
SELECT DATABASE() AS target_database, UTC_TIMESTAMP(6) AS evaluated_at;
SELECT COUNT(*) AS users,
       COALESCE(SUM(a.user_id IS NULL),0) AS missing,
       COALESCE(SUM(a.source='LEGACY'),0) AS legacy,
       COALESCE(SUM(a.source='ASSIGNMENT'),0) AS assignment,
       COALESCE(SUM(a.user_id IS NOT NULL AND
         (a.source NOT IN ('LEGACY','ASSIGNMENT') OR a.version NOT REGEXP '[^[:space:]]')),0) AS corrupt
FROM user_profile u LEFT JOIN rbac_authorization_authority a ON a.user_id=u.id;
SELECT source, COUNT(*) AS authorities FROM rbac_authorization_authority GROUP BY source;
SELECT lifecycle,readiness,grantable,COUNT(*) AS permissions
FROM rbac_permission_definition GROUP BY lifecycle,readiness,grantable;
SELECT lifecycle,readiness,grantable,COUNT(*) AS roles
FROM rbac_role GROUP BY lifecycle,readiness,grantable;
-- Initial migration requires missing=0 and corrupt=0. Current startup additionally
-- requires legacy=0 and exactly one unrevoked ROOT. These counts do not approve source switching.
