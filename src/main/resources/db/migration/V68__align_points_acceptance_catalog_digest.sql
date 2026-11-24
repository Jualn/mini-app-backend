-- V67 registers the acceptance steward but left pre-existing definitions at the prior digest.
-- Keep V67 immutable after application. Only advance the known prior catalogue identity;
-- never change lifecycle, readiness, grantability, assignments or organization admission here.
UPDATE rbac_permission_definition
SET catalog_digest='2a08a320843a0f536c7449df025d1751781374cacbbc84654668b80a42466aae'
WHERE catalog_digest='ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424';
UPDATE rbac_role
SET catalog_digest='2a08a320843a0f536c7449df025d1751781374cacbbc84654668b80a42466aae'
WHERE catalog_digest='ea830aeddf262f2099d892e6f4cff290ffcc7ff29220abe6edad4f0a736b1424';
