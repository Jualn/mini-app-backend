package cn.jualn.miniapp.module.comment.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.comment.service.*;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.service.PostPublicAccess;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {
    private static final Set<TargetType> ALLOWED_TARGET_TYPES = Set.of(TargetType.POST, TargetType.ACTIVITY, TargetType.EXAM);
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.comment.service.MentorAnswers> mentorAnswers;
    private final CurrentAuthorization authorization;
    private final PostQaCommentPolicy qaPolicy;
    private final CommentMapper commentMapper;
    private final PostPublicAccess publicAccess;
    private final UserAccountService userService;
    private final CommentTargetAccess targetAccess;
    private final AuditReservationService auditReservationService;
    private final MediaService mediaService;
    private final CanonicalCommentService canonicalComments;
    private final CommentCounters counters;
    private final cn.jualn.miniapp.module.comment.mapper.CommunityCommentMapper community;
    @Autowired
    private ObjectProvider<ContentSafetyInvalidation> safetyReviews;

    @Transactional(rollbackFor = Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Long createComment(CommentCreateBO command) {
        if (command == null) {
            throw new BusinessException(ResultCode.COMMENT_PARAM_INVALID, "请求参数不能为空");
        }

        Long userId = requireUserId();

        boolean formal=command.getTargetType()==TargetType.POST && command.getParentId()==null && publicAccess.isQa(command.getTargetId());
        if(!formal && command.getQuestionVersion()!=null)throw PostProblems.invalid("questionVersion","INVALID_VALUE","Only a formal mentor answer accepts questionVersion");
        if(formal && (command.getQuestionVersion()==null || command.getQuestionVersion().isBlank()))throw PostProblems.invalid("questionVersion","REQUIRED","A formal answer requires the current question version");
        if(formal)mentorAnswers.getObject().guard(userId);
        if(command.getTargetType()==TargetType.POST && publicAccess.isNormal(command.getTargetId())) userService.lockCommunityWriter(userId);
        else userService.assertContentCreationAllowed(userId);
        TargetType targetType = command.getTargetType();
        long start = System.currentTimeMillis();

        assertTargetType(command.getTargetType());
        targetAccess.requireSubject(targetType, command.getTargetId(), true);
        var postAccess = targetType == TargetType.POST ? publicAccess.require(command.getTargetId(), true) : null;
        if (postAccess != null && "MENTOR_QA".equals(postAccess.postType()) && command.getParentId() == null && command.getReplyToUid() != null)
            throw PostProblems.invalid("replyToUid", "INVALID_VALUE", "A formal answer has no reply target");


        Comment parent = null;
        if (command.getParentId() != null) {
            parent = targetAccess.requireReplyRoot(command.getParentId(), targetType, command.getTargetId(), postAccess!=null && command.getReplyToCommentId()!=null);
        }

        Comment replyTarget = null;
        if (command.getReplyToCommentId() != null) {
            if (parent == null)
                throw PostProblems.invalid("parentId", "REQUIRED", "A reply target requires a root parent");
            replyTarget = commentMapper.lockReplyTarget(command.getReplyToCommentId());
            if (replyTarget == null || !Objects.equals(replyTarget.getTargetType(), targetType.getCode())
                    || !Objects.equals(replyTarget.getTargetId(), command.getTargetId())
                    || !(Objects.equals(replyTarget.getId(), parent.getId()) || Objects.equals(replyTarget.getParentId(), parent.getId()))
                    || !Objects.equals(replyTarget.getStatus(), CommentStatus.NORMAL.getCode()))
                throw PostProblems.invalid("replyToCommentId", "INVALID_VALUE", "Reply target must be visible in the same post and root");
        }
        if(postAccess!=null && replyTarget!=null) targetAccess.requirePublic(replyTarget.getId(),true);
        if (postAccess != null && "MENTOR_QA".equals(postAccess.postType()) && replyTarget != null && command.getReplyToUid() != null && !Objects.equals(command.getReplyToUid(), replyTarget.getUserId()))
            throw PostProblems.invalid("replyToUid", "INVALID_VALUE", "Reply author does not match the concrete target");
        String mentorEvidence = postAccess == null ? null : qaPolicy.authorize(postAccess, userId, parent, replyTarget);
        String attribution=formal?mentorAnswers.getObject().accept(userId,command.getTargetId(),command.getQuestionVersion()):null;
        Long replyUid = replyTarget == null ? resolveReplyToUid(command.getReplyToUid(), parent) : replyTarget.getUserId();

        String imageUrl = null;
        if (StringUtils.hasText(command.getImageObjectKey())) {
            imageUrl = mediaService.resolveOwnedUploadUrl(TargetType.COMMENT, command.getImageObjectKey());
        } else if (StringUtils.hasText(command.getImageUrl())) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "评论图片缺少合法 objectKey");
        }

        Comment comment = Comment.builder()
                .questionVersion(postAccess!=null && "MENTOR_QA".equals(postAccess.postType())?publicAccess.questionVersion(command.getTargetId()):null).mentorAttribution(attribution).communityThread(postAccess!=null).targetType(targetType.getCode())
                .targetId(command.getTargetId())
                .userId(userId)
                .parentId(command.getParentId())
                .replyToUid(replyUid)
                .replyToCommentId(command.getReplyToCommentId()).mentorEvidence(mentorEvidence)
                .content(command.getContent())
                .imageUrl(imageUrl)
                .imageObjectKey(StringUtils.hasText(command.getImageObjectKey())
                        ? command.getImageObjectKey().trim()
                        : null)
                .status(CommentStatus.PENDING.getCode())
                .auditStatus(AuditStatus.PENDING.getCode())
                .build();

        commentMapper.insert(comment);

        if (StringUtils.hasText(comment.getImageObjectKey())) {
            mediaService.bindPendingUploads(
                    TargetType.COMMENT, comment.getId(), List.of(comment.getImageObjectKey()));
        }

        AuditReserveResultBO reserveResult = auditReservationService.reserveAuditLogs(
                AuditReserveBO.builder()
                        .auditScene(AuditScene.COMMENT)
                        .targetId(comment.getId())
                        .textContent(comment.getContent())
                        .mediaItems(buildCommentAuditMediaItems(comment.getImageUrl()))
                        .build()
        );

        if (!reserveResult.hasAuditTask()) {
            // 理论上不应该发生，除非允许空内容 + 无图片评论。
            // 这里直接激活，避免评论永久 PENDING。
//            activateCommentAfterAuditPass(comment.getId());
        }

        // 注意：评论审核通过后再增加 commentCount / replyCount，并发送评论/回复通知。
        // 这里不要提前产生对外副作用，避免审核拒绝后计数虚高、通知不可撤回。

        log.debug("[CommentService.createComment][完成] userId={}, commentId={}, costMs={}",
                userId, comment.getId(), System.currentTimeMillis() - start);

        return comment.getId();
    }

    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void removeComment(Long commentId) {
        Long operatorId = requireUserId();

        Comment comment = requireComment(commentId);
        if(comment.getTargetType()==1 && publicAccess.isGrowth(comment.getTargetId()))publicAccess.require(comment.getTargetId(),true);
        if(comment.getTargetType()==1 && publicAccess.isNormal(comment.getTargetId())) { targetAccess.lockForRemoval(commentId);comment=requireComment(commentId); }

        if (Integer.valueOf(TargetType.ARTICLE.getCode()).equals(comment.getTargetType())) {
            canonicalComments.delete(comment.getTargetId(), commentId);
            return;
        }

        if(comment.getTargetType()==1 && targetAccess.isNormalPostComment(commentId)) {
            if(!Objects.equals(operatorId,comment.getUserId())) throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        } else assertCanManageComment(operatorId, comment);

        int deleteStatus = Objects.equals(operatorId, comment.getUserId())
                ? CommentStatus.USER_DELETED.getCode()
                : CommentStatus.ADMIN_DELETED.getCode();

        commentMapper.update(new LambdaUpdateWrapper<Comment>()
                .eq(Comment::getId, commentId)
                .set(Comment::getStatus, deleteStatus)
                .set(Comment::getDeletedAt, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))));

        safetyReviews.getObject().invalidateTarget(4, commentId, "target-deleted");
        boolean shouldDecreaseCount =
                Objects.equals(comment.getStatus(), CommentStatus.NORMAL.getCode());

        if (shouldDecreaseCount)
            counters.decrease(comment.getTargetType(), comment.getTargetId(), comment.getParentId());

        log.info("[CommentService.removeComment][完成] operatorId={}, commentId={}", operatorId, commentId);
    }

    private List<AuditReserveBO.MediaItem> buildCommentAuditMediaItems(String imageUrl) {
        if (!StringUtils.hasText(imageUrl)) {
            return List.of();
        }

        return List.of(AuditReserveBO.MediaItem.builder()
                // 和你 AuditServiceImpl.doMediaCheck 里 mediaType(2) 保持一致。
                // TODO 如果后续抽枚举，可替换为 MediaType.IMAGE.getCode()。
                .mediaType(MediaType.IMAGE)
                .mediaUrl(imageUrl)
                .build());
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private void assertTargetType(TargetType targetType) {
        if (!ALLOWED_TARGET_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.INVALID_TARGET_TYPE, "targetType 无效");
        }
    }

    private Comment requireComment(Long commentId) {
        if (commentId == null) {
            throw new BusinessException(ResultCode.COMMENT_PARAM_INVALID, "commentId 不能为空");
        }
        Comment comment = commentMapper.selectById(commentId);
        if(comment==null) comment=community.get(commentId);
        if (comment == null || (comment.getDeletedAt() != null && comment.getStatus()!=4)) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND, "评论不存在");
        }
        return comment;
    }

    private Long resolveReplyToUid(Long replyToUid, Comment parent) {
        if (parent == null) {
            return null;
        }
        return replyToUid != null ? replyToUid : parent.getUserId();
    }

    private void assertCanManageComment(Long operatorId, Comment comment) {
        if (Objects.equals(operatorId, comment.getUserId())) {
            return;
        }
        authorization.requireLegacyEntry(operatorId, "content:manage", cn.jualn.miniapp.module.comment.service.CommentTargetAccess.namespace(comment.getTargetType()), "comment:dispose");
    }
}
