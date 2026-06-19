package cn.jualn.miniapp.module.comment.service.impl;

import cn.jualn.miniapp.common.annotation.AuditTarget;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.wx.notice.data.AuditResultNoticeData;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@AuditTarget(TargetType.COMMENT)
public class CommentAuditCallback implements AuditResultCallback {

    private final CommentMapper commentMapper;
    private final ContentAuditLogMapper contentAuditLogMapper;
    private final QueueProducer queueProducer;

    @Override
    public void onPass(Long commentId) {
        long total = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, commentId)
                        .eq(ContentAuditLog::getTargetType, TargetType.COMMENT)
        );

        long passed = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, commentId)
                        .eq(ContentAuditLog::getTargetType, TargetType.COMMENT)
                        .eq(ContentAuditLog::getFinalResult, 1)
        );

        if (passed < total) {
            return;
        }

        commentMapper.update(
                new LambdaUpdateWrapper<Comment>()
                        .set(Comment::getStatus, CommentStatus.NORMAL.getCode())
                        .set(Comment::getAuditStatus, AuditStatus.PASS.getCode())
                        .eq(Comment::getId, commentId)
                        .eq(Comment::getStatus, CommentStatus.PENDING.getCode())
        );
    }

    @Override
    public void onReject(Long targetId, String reason) {
        commentMapper.update(
                new LambdaUpdateWrapper<Comment>()
                        .set(Comment::getAuditStatus, AuditStatus.REJECT)
                        .set(Comment::getStatus, CommentStatus.REJECTED)
                        .eq(Comment::getTargetId, targetId)
        );

        // 发送审核拒绝通知
        sendAuditRejectNotification(targetId, reason, TargetType.COMMENT);
    }

    private void sendAuditRejectNotification(Long targetId, String reason, TargetType targetType) {
        try {
            Comment comment = commentMapper.selectById(targetId);
            if (comment == null) return;

            NotifyPayload payload = NotifyPayload.builder()
                    .receiverId(comment.getUserId())
                    .senderId(null)
                    .type(NotifyType.AUDIT_RESULT)
                    .title("你的评论未通过审核")
                    .content(reason != null ? reason : "内容不符合社区规范")
                    .targetType(targetType)
                    .targetId(targetId)
                    .wxData(new AuditResultNoticeData(
                            truncate(comment.getContent(), 20),
                            "未通过",
                            reason != null ? reason : "内容不符合社区规范",
                            LocalDateTime.now()))
                    .build();
            queueProducer.send(payload);
        } catch (Exception e) {
            log.warn("[CommentAudit] 审核通知发送失败，commentId={}", targetId, e);
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
