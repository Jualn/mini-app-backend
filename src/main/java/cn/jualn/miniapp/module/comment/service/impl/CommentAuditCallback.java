package cn.jualn.miniapp.module.comment.service.impl;

import cn.jualn.miniapp.common.annotation.AuditTarget;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.user.service.UserService;
import cn.jualn.miniapp.module.wx.notice.data.CommentNoticeData;
import cn.jualn.miniapp.module.wx.notice.data.ReplyNoticeData;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@AuditTarget(AuditScene.COMMENT)
public class CommentAuditCallback implements AuditResultCallback {

    private final CommentMapper commentMapper;
    private final ContentAuditLogMapper contentAuditLogMapper;
    private final NotifyService notifyService;
    private final PostMapper postMapper;
    private final ActivityMapper activityMapper;
    private final ExamInfoMapper examInfoMapper;
    private final UserService userService;
    private final ActivityService activityService;
    private final ExamService examService;
    private final RedisService redisService;

    @Override
    public void onPass(Long commentId, Long auditLogId) {
        long total = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, commentId)
                        .eq(ContentAuditLog::getTargetType, AuditScene.COMMENT.getCode())
        );

        if (total <= 0) {
            return;
        }

        long rejected = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, commentId)
                        .eq(ContentAuditLog::getTargetType, AuditScene.COMMENT.getCode())
                        .eq(ContentAuditLog::getFinalResult, AuditStatus.REJECT.getCode())
        );

        if (rejected > 0) {
            return;
        }

        long passed = contentAuditLogMapper.selectCount(
                new LambdaQueryWrapper<ContentAuditLog>()
                        .eq(ContentAuditLog::getTargetId, commentId)
                        .eq(ContentAuditLog::getTargetType, AuditScene.COMMENT.getCode())
                        .eq(ContentAuditLog::getFinalResult, AuditStatus.PASS.getCode())
        );

        if (passed < total) {
            return;
        }

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

        int rows = commentMapper.update(new LambdaUpdateWrapper<Comment>()
                .set(Comment::getStatus, CommentStatus.NORMAL.getCode())
                .set(Comment::getAuditStatus, AuditStatus.PASS.getCode())
                .eq(Comment::getId, commentId)
                .eq(Comment::getStatus, CommentStatus.PENDING.getCode())
                .eq(Comment::getAuditStatus, AuditStatus.PENDING.getCode()));

        if (rows <= 0) {
            return;
        }

        if (comment.getParentId() != null) {
            commentMapper.increaseReplyCount(comment.getParentId());
        }

        TargetType targetType = TargetType.fromCode(comment.getTargetType());
        if (targetType != null) {
            increaseTargetCommentCount(targetType, comment.getTargetId());
        }

        Comment parent = null;
        if (comment.getParentId() != null) {
            parent = commentMapper.selectById(comment.getParentId());
        }

        sendCommentNotification(comment, parent, comment.getUserId());

        log.info("[CommentService.activateCommentAfterAuditPass][完成] commentId={}", commentId);
    }

    private void increaseTargetCommentCount(TargetType type, Long targetId) {
        switch (type) {
            case POST -> {
                // 暂时不能调用 postService 有循环注入风险 TODO 后续得修复
                postMapper.incrementCommentCount(targetId);
                redisService.delete(RedisKeyConstant.postDetail(targetId));
            }
            case ACTIVITY -> activityService.increaseCommentCount(targetId);
            case EXAM -> examService.increaseCommentCount(targetId);
        }
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
                .wxData(new CommentNoticeData(
                        comment.getTargetId(),
                        comment.getId(),
                        content.getTitle() == null ? "" : content.getTitle(),
                        truncate(comment.getContent(), 20),
                        senderName,
                        LocalDateTime.now()))
                .build();

        notifyService.processNotificationPayload(payload);
    }

    private void sendReplyNotification(Comment comment, Comment parent, Long senderId) {
        Long receiverId = parent.getUserId();
        if (receiverId.equals(senderId)) return;

        TargetType targetType = TargetType.fromCode(comment.getTargetType());
        var actor = resolveActor(senderId);
        String senderName = actor == null ? "" : actor.nickname();
        var subject = getContentSummary(targetType, comment.getTargetId());

        NotifyPayload payload = NotifyPayload.builder()
                .receiverId(receiverId)
                .senderId(senderId)
                .type(NotifyType.COMMENT_REPLIED)
                .title("有人回复了你")
                .content(truncate(comment.getContent(), 50))
                .targetType(targetType)
                .targetId(comment.getTargetId())
                .sourceKey("reply:" + comment.getId() + ":receiver:" + receiverId + ":approved")
                .snapshot(commentSnapshot(comment, actor, truncate(parent.getContent(), 50), true,
                        subject == null ? null : subject.getTitle(), parent.getContent() == null ? null : truncate(parent.getContent(), 50)))
                .wxData(new ReplyNoticeData(
                        truncate(parent.getContent(), 20),
                        truncate(comment.getContent(), 20),
                        senderName,
                        LocalDateTime.now()))
                .build();

        notifyService.processNotificationPayload(payload);
    }

    private cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot commentSnapshot(
            Comment comment, cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Actor actor, String context,
            boolean reply, String subjectTitle, String quote) {
        String resourceId = comment.getTargetId().toString();
        TargetType targetType = TargetType.fromCode(comment.getTargetType());
        cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target target = null;
        if (targetType == TargetType.POST) {
            target = cn.jualn.miniapp.module.notify.service.NotificationSnapshotProjection.post(resourceId, comment.getId().toString());
        } else if (targetType == TargetType.ACTIVITY) {
            target = new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("ACTIVITY_DETAIL", null, null, resourceId, null);
        } else if (targetType == TargetType.EXAM) {
            target = new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Target("PUBLIC_EVENT_DETAIL", null, null, null, resourceId);
        }
        // Non-post replies have no newly promised semantic navigation in this Contract.
        if (reply && targetType != TargetType.POST) { target = null; }
        var subject = new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Subject(
                reply ? "COMMENT" : targetType == TargetType.EXAM ? "PUBLIC_EVENT" : targetType.name(),
                reply ? comment.getParentId().toString() : resourceId);
        return new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Snapshot(
                new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Presentation(
                        reply ? "有人回复了你" : "有人评论了你", truncate(comment.getContent(), 50), context, null, null, subjectTitle, quote),
                actor,
                subject, target);
    }

    private cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Actor resolveActor(Long userId) {
        try {
            var user = userService.getSimpleInfo(userId);
            if (user == null || user.getNickname() == null || user.getNickname().isBlank()) return null;
            String avatar = user.getAvatarUrl();
            return new cn.jualn.miniapp.module.notify.bo.NotificationCenterBO.Actor(userId.toString(), user.getNickname(),
                    avatar == null || avatar.isBlank() ? null : avatar);
        } catch (Exception e) {
            return null;
        }
    }

    private ContentSummary getContentSummary(TargetType type, Long targetId) {
        if (type == null || targetId == null) {
            return null;
        }

        return switch (type) {
            case POST -> {
                Post post = postMapper.selectOne(
                        new LambdaQueryWrapper<Post>()
                                .select(Post::getUserId, Post::getTitle)
                                .eq(Post::getId, targetId));
                yield post == null ? null : ContentSummary.builder()
                        .ownerId(post.getUserId())
                        .title(post.getTitle())
                        .build();
            }
            case ACTIVITY -> {
                Activity activity = activityMapper.selectOne(
                        new LambdaQueryWrapper<Activity>()
                        .select(Activity::getUserId, Activity::getTitle)
                        .eq(Activity::getId, targetId)
                );
                yield activity == null ? null : ContentSummary.builder()
                        .ownerId(activity.getUserId())
                        .title(activity.getTitle())
                        .build();
            }
            case EXAM -> {
                ExamInfo exam = examInfoMapper.selectOne(
                        new LambdaQueryWrapper<ExamInfo>()
                        .select(ExamInfo::getUserId, ExamInfo::getTitle)
                        .eq(ExamInfo::getId, targetId)
                );
                yield exam == null ? null : ContentSummary.builder()
                        .ownerId(exam.getUserId())
                        .title(exam.getTitle())
                        .build();
            }
            default -> null;
        };
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
