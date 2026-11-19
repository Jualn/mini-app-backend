-- Publish the implemented HOME capability from its initial V93 state.
START TRANSACTION;

UPDATE rbac_permission_definition
SET lifecycle = 'ENABLED', readiness = 'READY', grantable = 1, unavailable_reason = NULL
WHERE code = 'home-banner:manage' AND lifecycle = 'REGISTERED' AND readiness = 'NOT_READY'
    AND grantable = 0 AND unavailable_reason = 'PROVIDER_NOT_VERIFIED';

UPDATE rbac_role
SET lifecycle = 'ENABLED', readiness = 'READY', grantable = 1, unavailable_reason = NULL
WHERE code = 'HOME_BANNER_MANAGER_V1' AND lifecycle = 'REGISTERED' AND readiness = 'NOT_READY'
    AND grantable = 0 AND unavailable_reason = 'PROVIDER_NOT_VERIFIED'
    AND EXISTS (
        SELECT 1 FROM rbac_permission_definition
        WHERE code = 'home-banner:manage' AND lifecycle = 'ENABLED' AND readiness = 'READY' AND grantable = 1
    );

COMMIT;
