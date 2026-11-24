-- ROOT platform-owner policy metadata; no account/source/Assignment or readiness changes.
-- Deploy with the matching canonical catalog and new Reader; do not mix old catalog binaries.
UPDATE rbac_role SET name='平台所有者 ROOT' WHERE code='ROOT';
UPDATE rbac_permission_definition SET catalog_digest='db7135c49a66b9da05b1fab728d266a7bf0fd64492e0bac11c21aa780cc8f981' WHERE catalog_digest='cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c';
UPDATE rbac_role SET catalog_digest='db7135c49a66b9da05b1fab728d266a7bf0fd64492e0bac11c21aa780cc8f981' WHERE catalog_digest='cf6ac904e3c78a93a07f61fa7e7624ce21f1a4390d8ad2030fba76b808b8165c';
