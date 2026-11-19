package cn.jualn.miniapp.module.authorization.service;

/** Synthetic integration release only; production RbacCatalog still has an empty support list. */
public final class RbacIntegrationCatalog {
    private RbacIntegrationCatalog() {}
    public static RbacCatalog forArticle() {
        return new RbacCatalog(java.util.Set.of("audit-log:read","authorization:read","authorization:assignment-manage","authorization:root-manage",
                "article:read","article:edit","article:publish","article:unpublish","article-category:read","article-publisher:read",
                "content:read","media:read","media:write","official-content:read","official-content:edit","official-content:publish","official-content:unpublish"));
    }
    public static RbacCatalog create() {
        return new RbacCatalog(java.util.Set.of("audit-log:read","authorization:read","authorization:assignment-manage","authorization:root-manage",
            "notice:read","system:read","content:read","post:curate","post:dispose","comment:dispose","post-dictionary:read","post-dictionary:manage",
            "report:read","report:handle","user:read","user:manage","review:read","review:decide"));
    }
}
