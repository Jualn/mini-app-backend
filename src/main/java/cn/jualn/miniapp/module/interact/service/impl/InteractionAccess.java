package cn.jualn.miniapp.module.interact.service.impl;

import cn.jualn.miniapp.module.comment.service.CommentTargetAccess;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;


/** Interact 内部访问检查，委托主体 owner 并复核 Article 的当前账号资格。 */
@Service
@RequiredArgsConstructor
public class InteractionAccess {
    private static final Set<TargetType> LIKE_ALLOWED_TYPES =
            Set.of(TargetType.POST, TargetType.ACTIVITY, TargetType.COMMENT, TargetType.EXAM, TargetType.ARTICLE);
    private static final Set<TargetType> CONTENT_ONLY_TYPES =
            Set.of(TargetType.POST, TargetType.ACTIVITY, TargetType.EXAM, TargetType.ARTICLE);
    private final UserAccountService userAccounts;
    private final CommentTargetAccess commentTargetAccess;

    public void guardPublic(TargetType type, Long id, boolean mutation) {
        if (type == TargetType.ARTICLE) requireLogin();
        if (type == TargetType.COMMENT) {
            if (commentTargetAccess.articleId(id) != null) requireLogin();
            commentTargetAccess.requirePublic(id, mutation);
        } else {
            commentTargetAccess.requireSubject(type, id, mutation);
        }
    }

    /**
     * 获取当前登录用户 ID，未登录抛出 UNAUTHORIZED。
     */
    public Long requireLogin() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        // The legacy count route is anonymous, but Article counts require login.
        // Enforce current account eligibility here as well as in the HTTP guard.
        userAccounts.assertLoginAllowed(userId);
        return userId;
    }

    /**
     * 断言目标类型属于既有点赞允许集合。
     */
    public void assertLikeAllowed(TargetType targetType) {
        if (!LIKE_ALLOWED_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.INVALID_TARGET_TYPE, "该目标类型不支持点赞");
        }
    }

    /**
     * 断言目标类型为内容型（POST / ACTIVITY / EXAM / ARTICLE），COMMENT 不支持分享和浏览。
     */
    public void assertContentOnly(TargetType targetType) {
        if (!CONTENT_ONLY_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.INVALID_TARGET_TYPE, "该目标类型不支持此操作");
        }
    }

    public boolean canRemoveUnavailable(TargetType type,long id) {commentTargetAccess.requireGrowthIfAny(type,id);return type==TargetType.POST && commentTargetAccess.isNormalPost(id) || type==TargetType.COMMENT && commentTargetAccess.isNormalPostComment(id); }
    public boolean isArticleComment(long id) { return commentTargetAccess.articleId(id)!=null; }

    public boolean supportsLike(TargetType type) { return LIKE_ALLOWED_TYPES.contains(type); }
}
