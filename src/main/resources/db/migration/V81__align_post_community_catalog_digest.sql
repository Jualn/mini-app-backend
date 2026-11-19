-- Only the three documented descriptions changed. Role members, scope and grant gates do not.
-- Advance the complete known prior artifact; unknown/mixed artifacts still fail RbacCatalog.verify.
UPDATE rbac_permission_definition
SET catalog_digest='620bcf59e0b93c40460bc503cbdebfb426c6326acdc929bc4448329928b3409e'
WHERE catalog_digest='2a08a320843a0f536c7449df025d1751781374cacbbc84654668b80a42466aae';
UPDATE rbac_role
SET catalog_digest='620bcf59e0b93c40460bc503cbdebfb426c6326acdc929bc4448329928b3409e'
WHERE catalog_digest='2a08a320843a0f536c7449df025d1751781374cacbbc84654668b80a42466aae';

UPDATE rbac_permission_definition SET description='置顶/精选及取消；普通社区NORMAL分类归属维护，不含字典维护或用户原文改写' WHERE code='post:curate' AND catalog_digest='620bcf59e0b93c40460bc503cbdebfb426c6326acdc929bc4448329928b3409e';

UPDATE rbac_permission_definition SET description='管理员下架及安全恢复NORMAL；恢复须来源可证明、未作者删除且当前安全通过，不含作者终止删除' WHERE code='post:dispose' AND catalog_digest='620bcf59e0b93c40460bc503cbdebfb426c6326acdc929bc4448329928b3409e';

UPDATE rbac_permission_definition SET description='保留禁用的旧恢复候选别名；普通社区安全恢复按post:dispose与实际职责/范围，不自动启用本Permission' WHERE code='post:restore' AND catalog_digest='620bcf59e0b93c40460bc503cbdebfb426c6326acdc929bc4448329928b3409e';
