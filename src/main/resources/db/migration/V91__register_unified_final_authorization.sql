-- Unified final V1 catalog registration only. No source switch, ROOT repair or bulk READY.
-- Unknown catalog digests remain incompatible and are rejected by the provider.
ALTER TABLE activity_registration MODIFY invalid_reason VARCHAR(500) NULL COMMENT 'First administrative cancellation reason, retained permanently';
UPDATE rbac_permission_definition SET catalog_digest='01fc24663932eeb839b54ce107b6c1f1f5619bf1980627099a329b3780fc9392' WHERE catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
UPDATE rbac_role SET catalog_digest='01fc24663932eeb839b54ce107b6c1f1f5619bf1980627099a329b3780fc9392' WHERE catalog_digest='8b8b71d6b3aa812841695ee370ff45c5a1bf44c82750933e2576ffde98918748';
INSERT INTO rbac_role(code,name,namespaces,lifecycle,readiness,grantable,unavailable_reason,catalog_digest) VALUES('REGISTRATION_DISPOSER_V1','活动报名作废员','["ACTIVITY"]','REGISTERED','NOT_READY',0,'PROVIDER_NOT_VERIFIED','01fc24663932eeb839b54ce107b6c1f1f5619bf1980627099a329b3780fc9392');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('REGISTRATION_DISPOSER_V1','activity:registration:dispose');
INSERT INTO rbac_role_permission(role_code,permission_code) VALUES('REGISTRATION_DISPOSER_V1','activity:registration:read');
