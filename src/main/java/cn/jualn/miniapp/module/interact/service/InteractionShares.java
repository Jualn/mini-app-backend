package cn.jualn.miniapp.module.interact.service;

import cn.jualn.miniapp.module.interact.service.impl.InteractionAccess;
import cn.jualn.miniapp.module.article.service.ArticleService;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.interact.entity.ShareRecord;
import cn.jualn.miniapp.module.interact.mapper.ShareRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;


/** 分享记录写入及 Article 的既有统计重建。 */
@Service
@RequiredArgsConstructor
public class InteractionShares {
    private final ShareRecordMapper shareRecordMapper;
    private final ArticleService articleService;
    private final InteractionAccess access;
    private final cn.jualn.miniapp.module.interact.mapper.PostInteractionMapper postFacts;

    @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void share(TargetType type,Long id,Integer platform,String action,String eventId) {
        if(action==null && eventId==null) { share(type,id,platform); return; }
        if(type!=TargetType.POST || action==null || eventId==null || eventId.isBlank() || eventId.length()>128
                || !java.util.Set.of("WECHAT_SHARE_INVOKED","POSTER_SAVED").contains(action)
                || ("WECHAT_SHARE_INVOKED".equals(action) && (platform==null || (platform!=1 && platform!=2))))
            throw cn.jualn.miniapp.module.post.PostProblems.invalid("action","INVALID_VALUE","Invalid observable share event");
        long actor=access.requireLogin();
        access.guardPublic(type,id,true);
        if(!access.canRemoveUnavailable(type,id)) throw cn.jualn.miniapp.module.post.PostProblems.invalid("action","INVALID_VALUE","Observable events require a NORMAL post");
        var old=postFacts.share(actor,eventId);
        if(old!=null) {
            if(!java.util.Objects.equals(old.postId(),id) || !action.equals(old.action()) || !java.util.Objects.equals(platform,old.platform()))
                throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("idempotency-key-reused","Share event already used");
            return;
        }
        try { postFacts.shareEvent(actor,eventId,id,action,platform); }
        catch(org.springframework.dao.DuplicateKeyException duplicate) {
            var winner=postFacts.share(actor,eventId);
            if(winner==null || !java.util.Objects.equals(winner.postId(),id) || !action.equals(winner.action()) || !java.util.Objects.equals(platform,winner.platform()))
                throw cn.jualn.miniapp.common.exception.ContractProblemException.conflict("idempotency-key-reused","Share event already used");
            return;
        }
        share(type,id,platform);
    }

    /**
     * 记录分享行为。
     *
     * <p>仅支持内容型目标（POST / ACTIVITY / EXAM / ARTICLE），不支持 COMMENT。
     * 此入口写入分享记录；计数查询由 InteractionCounts 负责，Article 保留既有统计重建。
     *
     * @param targetType 目标类型
     * @param targetId   目标 ID
     * @param platform   分享平台编码
     */
    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void share(TargetType targetType, Long targetId, Integer platform) {
        Long userId = access.requireLogin();
        access.assertContentOnly(targetType);
        access.guardPublic(targetType, targetId, true);

        shareRecordMapper.insert(ShareRecord.builder()
                .userId(userId)
                .targetType(targetType.getCode())
                .targetId(targetId)
                .platform(platform)
                .build());
        // 普通分享计数查询记录；Article 保留既有统计重建。
        if (targetType == TargetType.ARTICLE) articleService.rebuildStatistics(targetId);
    }
}
