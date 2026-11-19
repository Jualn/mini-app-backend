package cn.jualn.miniapp.module.comment.service.impl;

import cn.jualn.miniapp.module.audit.annotation.AuditTarget;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.module.interact.service.InteractionSubjects;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.service.AuditLogService;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationSnapshotProjection;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.notify.payload.data.CommentNoticeData;
import cn.jualn.miniapp.module.notify.payload.data.ReplyNoticeData;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
@AuditTarget(AuditScene.COMMENT)
public class CommentAuditCallback implements AuditResultCallback {

    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.comment.service.MentorAnswers> mentorAnswers;
    private final CommentMapper commentMapper;
    private final AuditLogService auditLogs;
    private final NotifyService notifyService;
    private final UserService userService;
    private final InteractionSubjects subjects;
    private final cn.jualn.miniapp.module.comment.service.CommentTargetAccess targetAccess;
    private final cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper community;

    @Override
    public void onPass(Long commentId, Long auditLogId) {
        if (!auditLogs.allPassed(AuditScene.COMMENT, commentId)) return;

        activateCommentAfterAuditPass(commentId);
    }

    @Override
    public void onReject(Long commentId, Long auditLogId, String reason) {
        int rows = commentMapper.update(
                new LambdaUpdateWrapper<Comment>()
                        .set(Comment::getAuditStatus, AuditStatus.PENDING.getCode())
                        .set(Comment::getStatus, CommentStatus.PENDING.getCode())
                        .eq(Comment::getId, commentId)
                        .eq(Comment::getStatus, CommentStatus.PENDING.getCode())
        );

        if (rows <= 0) {
            return;
        }

        log.info("[CommentAudit] 机器风险内容已转人工复核，commentId={}", commentId);
    }

    public void activateCommentAfterAuditPass(Long commentId) {
        if (commentId == null) {
            return;
        }

        Comment comment = commentMapper.selectById(commentId);
        if (comment == null) {
            return;
        }

        if(targetAccess.isQaPostComment(commentId) && comment.getParentId()==null)mentorAnswers.getObject().guardPublication(commentId);
        targetAccess.lockForRemoval(commentId);
        comment = commentMapper.selectById(commentId);
        if(comment==null || comment.getStatus()!=0 || comment.getAuditStatus()!=0)return;
        targetAccess.requireSubject(TargetType.fromCode(comment.getTargetType()),comment.getTargetId(),true);
        if(comment.getParentId()!=null) {
            var root=community.get(comment.getParentId());
            if(root==null || (root.getStatus()!=1 && !(Boolean.TRUE.equals(comment.getCommunityThread()) && java.util.Set.of(3,4).contains(root.getStatus())))) return;
        }
        boolean formal=targetAccess.isQaPostComment(commentId) && comment.getParentId()==null;
        if(formal && !mentorAnswers.getObject().publish(commentId)) {
            commentMapper.rejectAdminReview(commentId);return;
        }
        int rows = commentMapper.update(new LambdaUpdateWrapper<Comment>()
                .set(Comment::getStatus, CommentStatus.NORMAL.getCode())
                .set(Comment::getAuditStatus, AuditStatus.PASS.getCode())
                .eq(Comment::getId, commentId)
                .eq(Comment::getStatus, CommentStatus.PENDING.getCode())
                .eq(Comment::getAuditStatus, AuditStatus.PENDING.getCode()));

        if (rows <= 0) {
            return;
        }

        if(formal)community.firstPublished(commentId);
        if (comment.getParentId() != null) {
            commentMapper.increaseReplyCount(comment.getParentId());
        }

        TargetType targetType = TargetType.fromCode(comment.getTargetType());
        if (targetType != null) {
            increaseTargetCommentCount(targetType, comment.getTargetId());
        }

        Comment parent = null;
        if (comment.getParentId() != null) {
            parent = community.get(comment.getParentId());
        }

        sendCommentNotification(comment, parent, comment.getUserId());

        log.info("[CommentService.activateCommentAfterAuditPass][完成] commentId={}", commentId);
    }

    private void increaseTargetCommentCount(TargetType type, Long targetId) {
        if (type == TargetType.POST || type == TargetType.ACTIVITY || type == TargetType.EXAM)
            subjects.require(type).commentApproved(targetId);
    }

    private void sendCommentNotification(Comment comment, Comment parent, Long senderId) {
        if (parent != null) {
            sendReplyNotification(comment, parent, senderId);
        } else {
            sendTopLevelCommentNotification(comment, senderId);
        }
    }

    private void sendTopLevelCommentNotification(Comment comment, Long senderId) {
        TargetType targetType = TargetType.fromCode(comment.getTargetType());
        ContentSummary content = getContentSummary(targetType, comment.getTargetId());

        if (content == null || content.getOwnerId() == null || content.getOwnerId().equals(senderId)) {
            return;
        }

        var actor = resolveActor(senderId);
        String senderName = actor == null ? "" : actor.nickname();

        NotifyPayload payload = NotifyPayload.builder()
                .receiverId(content.getOwnerId())
                .senderId(senderId)
                .type(targetType == TargetType.POST ? NotifyType.POST_COMMENTED : NotifyType.COMMENTED_ME)
                .title("有人评论了你")
                .content(truncate(comment.getContent(), 50))
                .targetType(targetType)
                .targetId(comment.getTargetId())
                .sourceKey("comment:" + comment.getId() + ":receiver:" + content.getOwnerId() + ":approved")
                .snapshot(commentSnapshot(comment, actor, content.getTitle(), false, content.getTitle(), null))
                .contentData(new CommentNoticeData(
                        comment.getTargetId(),
                        comment.getId(),
                        content.getTitle() == null ? "" : content.getTitle(),
                        truncate(comment.getContent(), 20),
                        senderName,
                        LocalDateTime.now(ZoneId.of("Asia/Shanghai"))))
                .build();

        notifyService.processNotificationPayload(payload);
    }

    private void sendReplyNotification(Comment comment, Comment parent, Long senderId) {
        var concreteTarget = community.get(comment.getReplyToCommentId()==null ? parent.getId() : comment.getReplyToCommentId());
        if(concreteTarget==null || concreteTarget.getStatus()!=1 || concreteTarget.getDeletedAt()!=null) return;
        Long receiverId = concreteTarget.getUserId();
        if (receiverId == null) return;
        if (receiverId.equals(senderId)) return;

        TargetType targetType = TargetType.fromCode(comment.getTargetType());
        var actor = resolveActor(senderId);
        String senderName = actor == null ? "" : actor.nickname();
        var subject = getContentSummary(targetType, comment.getTargetId());
        Comment quoted = parent;
        if (comment.getReplyToCommentId() != null) {
            var concrete = commentMapper.selectById(comment.getReplyToCommentId());
            if (concrete != null) quoted = concrete;
        }

        NotifyPayload payload = NotifyPayload.builder()
                .receiverId(receiverId)
                .senderId(senderId)
                .type(NotifyType.COMMENT_REPLIED)
                .title("有人回复了你")
                .content(truncate(comment.getContent(), 50))
                .targetType(targetType)
                .targetId(comment.getTargetId())
                .sourceKey("reply:" + comment.getId() + ":receiver:" + receiverId + ":approved")
                .snapshot(commentSnapshot(comment, actor, truncate(quoted.getContent(), 50), true,
                        subject == null ? null : subject.getTitle(), quoted.getContent() == null ? null : truncate(quoted.getContent(), 50)))
                .contentData(new ReplyNoticeData(
                        truncate(quoted.getContent(), 20),
                        truncate(comment.getContent(), 20),
                        senderName,
                        LocalDateTime.now(ZoneId.of("Asia/Shanghai"))))
                .build();

        notifyService.processNotificationPayload(payload);
    }

    private NotificationCenterBO.Snapshot commentSnapshot(
            Comment comment, NotificationCenterBO.Actor actor, String context,
            boolean reply, String subjectTitle, String quote) {
        String resourceId = comment.getTargetId().toString();
        TargetType targetType = TargetType.fromCode(comment.getTargetType());
        NotificationCenterBO.Target target = null;
        if (targetType == TargetType.POST) {
            target = NotificationSnapshotProjection.post(resourceId, comment.getId().toString());
        } else if (targetType == TargetType.ACTIVITY) {
            target = new NotificationCenterBO.Target("ACTIVITY_DETAIL", null, null, resourceId, null);
        } else if (targetType == TargetType.EXAM) {
            target = new NotificationCenterBO.Target("PUBLIC_EVENT_DETAIL", null, null, null, resourceId);
        }
        // Non-post replies have no newly promised semantic navigation in this Contract.
        if (reply && targetType != TargetType.POST) {
            target = null;
        }
        var subject = new NotificationCenterBO.Subject(
                reply ? "COMMENT" : targetType == TargetType.EXAM ? "PUBLIC_EVENT" : targetType.name(),
                reply ? comment.getParentId().toString() : resourceId);
        return new NotificationCenterBO.Snapshot(
                new NotificationCenterBO.Presentation(
                        reply ? "有人回复了你" : "有人评论了你", truncate(comment.getContent(), 50), context, null, null, subjectTitle, quote),
                actor,
                subject, target);
    }

    private NotificationCenterBO.Actor resolveActor(Long userId) {
        try {
            var user = userService.getSimpleInfo(userId);
            if (user == null || user.getNickname() == null || user.getNickname().isBlank()) return null;
            String avatar = user.getAvatarUrl();
            return new NotificationCenterBO.Actor(userId.toString(), user.getNickname(),
                    avatar == null || avatar.isBlank() ? null : avatar);
        } catch (Exception e) {
            return null;
        }
    }

    private ContentSummary getContentSummary(TargetType type, Long targetId) {
        if (type == null || targetId == null) {
            return null;
        }

        if (type != TargetType.POST && type != TargetType.ACTIVITY && type != TargetType.EXAM) return null;
        var summary = subjects.require(type).notificationSummary(targetId);
        return summary == null ? null : ContentSummary.builder().ownerId(summary.ownerId()).title(summary.title()).build();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    @Data
    @Builder
    private static class ContentSummary {
        private Long ownerId;
        private String title;
    }
}
