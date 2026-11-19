package cn.jualn.miniapp.module.post.service.impl;

import cn.jualn.miniapp.module.audit.annotation.AuditTarget;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.service.AuditLogService;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.enums.PostStatus;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@AuditTarget(AuditScene.POST)
public class PostAuditCallback implements AuditResultCallback {

    private final PostMapper postMapper;
    private final AuditLogService auditLogs;
    private final RedisService redisService;

    @Override
    public void onPass(Long postId, Long auditLogId) {
        if (!auditLogs.allPassed(AuditScene.POST, postId)) return;

        postMapper.update(
                new LambdaUpdateWrapper<Post>()
                        .set(Post::getAuditStatus, AuditStatus.PASS.getCode())
                        .eq(Post::getId, postId)
                        .eq(Post::getAuditRevision, 0L)
                        .eq(Post::getStatus, PostStatus.PUBLISHED.getCode())
                        .eq(Post::getAuditStatus, AuditStatus.PENDING.getCode())
        );
    }

    @Override
    public void onReject(Long postId, Long auditLogId, String reason) {
        int rows = postMapper.update(
                new LambdaUpdateWrapper<Post>()
                        .set(Post::getStatus, PostStatus.AUDITING.getCode())
                        .set(Post::getAuditStatus, AuditStatus.PENDING.getCode())
                        .set(Post::getRejectReason, null)
                        .eq(Post::getId, postId)
                        .eq(Post::getAuditRevision, 0L)
                        .ne(Post::getStatus, PostStatus.DELETED.getCode())
        );

        if (rows <= 0) {
            return;
        }

        redisService.delete(RedisKeyConstant.postDetail(postId));
        log.info("[PostAudit] 机器风险内容已转人工复核，postId={}", postId);
    }
}
