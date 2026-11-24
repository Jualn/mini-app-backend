package cn.jualn.miniapp.module.interact.service;

import cn.jualn.miniapp.module.interact.service.impl.LikeCache;
import cn.jualn.miniapp.module.interact.service.impl.LikeNotifications;
import cn.jualn.miniapp.module.interact.service.impl.InteractionAccess;
import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.interact.entity.LikeRecord;
import cn.jualn.miniapp.module.interact.mapper.LikeRecordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;



/** 点赞与取消点赞；记录、统计重建与通知意图保留在调用事务内，缓存提交后同步。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionLikes {
    private final LikeRecordMapper likeRecordMapper;
    private final ArticleService articleService;
    private final InteractionAccess access;
    private final LikeNotifications notifications;
    private final LikeCache cache;

    /**
     * 点赞目标内容。
     *
     * <p>流程：
     * <ol>
     *   <li>身份校验（必须登录）</li>
     *   <li>类型校验（沿既有允许集合）</li>
     *   <li>由主体 owner 检查当前公开资格，写操作在事务中锁定主体</li>
     *   <li>写 like_record，重复点赞幂等处理</li>
     *   <li>事务提交后同步 Redis 计数与状态缓存</li>
     * </ol>
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @throws BusinessException UNAUTHORIZED 未登录；BAD_REQUEST 类型不支持；NOT_FOUND 目标不存在
     */
    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void like(TargetType targetType, Long targetId) {
        Long userId = access.requireLogin();
        access.assertLikeAllowed(targetType);
        access.guardPublic(targetType, targetId, true);

        boolean inserted = false;
        try {
            inserted = likeRecordMapper.insert(LikeRecord.builder()
                    .userId(userId)
                    .targetType(targetType.getCode())
                    .targetId(targetId)
                    .build()) == 1;
        } catch (DuplicateKeyException e) {
            // 同一用户重复点赞：幂等，视为成功，不抛异常
            log.debug("[Interact.like] 幂等：userId={}, targetType={}, targetId={}",
                    userId, targetType, targetId);
        }

        if (targetType == TargetType.ARTICLE && inserted) articleService.rebuildStatistics(targetId);
        cache.afterChange(userId, targetType, targetId, true, inserted);

        if (inserted) {
            notifications.notifyLike(userId, targetType, targetId);
        }
    }

    /**
     * 取消点赞。
     *
     * <p>若用户本未点赞（记录不存在），不更新计数，接口幂等。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     */
    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void unlike(TargetType targetType, Long targetId) {
        Long userId = access.requireLogin();
        access.assertLikeAllowed(targetType);
        if (!access.canRemoveUnavailable(targetType,targetId)) access.guardPublic(targetType,targetId,true);

        int deleted = likeRecordMapper.delete(new LambdaQueryWrapper<LikeRecord>()
                .eq(LikeRecord::getUserId, userId)
                .eq(LikeRecord::getTargetType, targetType.getCode())
                .eq(LikeRecord::getTargetId, targetId));

        cache.afterChange(userId, targetType, targetId, false, deleted > 0);
        if (targetType == TargetType.ARTICLE && deleted > 0) articleService.rebuildStatistics(targetId);
    }
}
