package cn.jualn.miniapp.module.article.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.authorization.service.RbacAuthorizationReader;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Article capabilities use the canonical ARTICLE namespace and fresh primary-database decisions. */
@Component
@RequiredArgsConstructor
public class ArticleAuthorization {
    private final RbacAuthorizationReader reader;
    private cn.jualn.miniapp.module.authorization.service.CurrentAuthorization current;
    @org.springframework.beans.factory.annotation.Autowired
    void current(org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.authorization.service.CurrentAuthorization> provider) {current=provider.getIfAvailable();}
    public enum Capability { READ, CREATE, EDIT, PUBLISH, UNPUBLISH, CATEGORY_READ, CATEGORY_MANAGE,
        PUBLISHER_READ, PUBLISHER_MANAGE, MEDIA_READ, MEDIA }

    public long require(Capability capability) {
        AdminStpUtil.STP_LOGIC.checkLogin();
        long operator = AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        String permission = switch (capability) {
            case READ -> "article:read";
            case CREATE, EDIT -> "article:edit";
            case PUBLISH -> "article:publish";
            case UNPUBLISH -> "article:unpublish";
            case CATEGORY_READ -> "article-category:read";
            case CATEGORY_MANAGE -> "article-category:manage";
            case PUBLISHER_READ -> "article-publisher:read";
            case PUBLISHER_MANAGE -> "article-publisher:manage";
            case MEDIA_READ -> "media:read";
            case MEDIA -> "media:write";
        };
        if(current!=null) {current.requireArticle(operator,permission);return operator;}
        if (!reader.read(operator).allows(permission, "ARTICLE")) {
            throw new ContractProblemException(HttpStatus.FORBIDDEN, "/problems/forbidden",
                    "Current Article capability is required");
        }
        return operator;
    }
}
