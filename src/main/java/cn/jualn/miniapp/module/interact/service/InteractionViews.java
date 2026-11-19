package cn.jualn.miniapp.module.interact.service;

import cn.jualn.miniapp.module.interact.service.impl.InteractionAccess;
import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.module.interact.mapper.ViewLogMapper;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;


/** 浏览写用例；普通主体使用 Redis 增量，Article 使用按用户和日期去重的浏览记录。 */
@Service
@RequiredArgsConstructor
public class InteractionViews {
    private final RedisService redisService;
    private final ArticleService articleService;
    private final ViewLogMapper viewLogMapper;
    private final InteractionAccess access;
    private final cn.jualn.miniapp.module.post.service.PostCommunityService community;
    private final cn.jualn.miniapp.module.interact.mapper.PostInteractionMapper postFacts;

    /**
     * 记录浏览行为。
     *
     * <p>普通主体的浏览设计（Article 另按用户和业务日期记录、去重并重建统计）：
     * <ul>
     *   <li>不写 view_log</li>
     *   <li>不做用户/设备/IP 去重</li>
     *   <li>Redis 只保存未同步增量 view_delta</li>
     *   <li>定时任务批量把 delta 加到内容表 view_count</li>
     * </ul>
     *
     * @param targetType 目标类型（POST / ACTIVITY / EXAM，不支持 COMMENT）
     * @param targetId   目标 ID
     */
    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void view(TargetType targetType, Long targetId) {
        access.assertContentOnly(targetType);
        access.guardPublic(targetType, targetId, true);

        if(targetType==TargetType.POST) {
            long actor=access.requireLogin();
            if(community.recordOpening(actor,targetId)) {
                var day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
                if(!postFacts.viewed(actor,targetId,day)) { postFacts.view(actor,targetId,day); community.countOpening(targetId); }
                return;
            }
        }
        if (targetType == TargetType.ARTICLE) {
            long userId = access.requireLogin();
            try {
                viewLogMapper.insert(cn.jualn.miniapp.module.interact.entity.ViewLog.builder().userId(userId)
                        .targetType(targetType.getCode()).targetId(targetId)
                        .viewDay(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))).build());
            } catch (DuplicateKeyException duplicate) {
                return;
            }
            articleService.rebuildStatistics(targetId);
            return;
        }

        String deltaKey = RedisKeyConstant.viewDelta(targetType.getKey(), targetId);

        redisService.incrementAndRefresh(deltaKey, RedisKeyConstant.VIEW_DELTA_TTL);
        redisService.sAdd(RedisKeyConstant.VIEW_DIRTY_SET, deltaKey);
    }
}
