package cn.jualn.miniapp.module.post.service.impl;

import cn.jualn.miniapp.common.annotation.AuditTarget;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.wx.notice.data.AuditResultNoticeData;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.common.enums.PostStatus;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@AuditTarget(AuditScene.POST)
public class PostAuditCallback implements AuditResultCallback {

    private final PostMapper postMapper;
    private final ContentAuditLogMapper contentAuditLogMapper;
    private final QueueProducer queueProducer;
    private final RedisService redisService;

    @Override
    public void onPass(Long postId) {
        long total = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, postId)
                        .eq(ContentAuditLog::getTargetType, AuditScene.POST.getCode())
        );

        if (total <= 0) {
            return;
        }

        long rejected = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, postId)
                        .eq(ContentAuditLog::getTargetType, AuditScene.POST.getCode())
                        .eq(ContentAuditLog::getFinalResult, AuditStatus.REJECT.getCode())
        );

        if (rejected > 0) {
            return;
        }

        long passed = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, postId)
                        .eq(ContentAuditLog::getTargetType, AuditScene.POST.getCode())
                        .eq(ContentAuditLog::getFinalResult, AuditStatus.PASS.getCode())
        );

        if (passed < total) {
            return;
        }

        postMapper.update(
                new LambdaUpdateWrapper<Post>()
                        .set(Post::getAuditStatus, AuditStatus.PASS.getCode())
                        .eq(Post::getId, postId)
                        .eq(Post::getStatus, PostStatus.PUBLISHED.getCode())
                        .eq(Post::getAuditStatus, AuditStatus.PENDING.getCode())
        );
    }

    @Override
    public void onReject(Long postId, String reason) {
        int rows = postMapper.update(
                new LambdaUpdateWrapper<Post>()
                        .set(Post::getStatus, PostStatus.REJECTED.getCode())
                        .set(Post::getAuditStatus, AuditStatus.REJECT.getCode())
                        .set(Post::getRejectReason, reason)
                        .eq(Post::getId, postId)
                        .ne(Post::getStatus, PostStatus.REJECTED.getCode())
                        .ne(Post::getStatus, PostStatus.DELETED.getCode())
        );

        if (rows <= 0) {
            return;
        }

        redisService.delete(RedisKeyConstant.postDetail(postId));
        sendAuditRejectNotification(postId, reason);
    }

    private void sendAuditRejectNotification(Long postId, String reason) {
        try {
            Post post = postMapper.selectById(postId);
            if (post == null) return;

            NotifyPayload payload = NotifyPayload.builder()
                    .receiverId(post.getUserId())
                    .senderId(null)
                    .type(NotifyType.AUDIT_RESULT)
                    .title("你的帖子未通过审核")
                    .content(reason != null ? reason : "内容不符合社区规范")
                    .targetType(TargetType.POST)
                    .targetId(postId)
                    .wxData(new AuditResultNoticeData(
                            truncate(post.getTitle(), 20),
                            "未通过",
                            reason != null ? reason : "内容不符合社区规范",
                            LocalDateTime.now()))
                    .build();
            queueProducer.send(payload);
        } catch (Exception e) {
            log.warn("[PostAudit] 审核通知发送失败，postId={}", postId, e);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
