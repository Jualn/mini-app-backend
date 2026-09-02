package cn.jualn.miniapp.module.comment.service.impl;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.payload.AuditMediaBatchPayload;
import cn.jualn.miniapp.module.audit.payload.AuditTextPayload;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;
import cn.jualn.miniapp.module.comment.bo.AdminCommentActionBO;
import cn.jualn.miniapp.module.comment.bo.CommentPageBO;
import cn.jualn.miniapp.module.comment.converter.CommentConverter;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.comment.mapper.AdminCommentStateRow;
import cn.jualn.miniapp.module.comment.service.CommentService;
import cn.jualn.miniapp.module.comment.vo.CommentVO;
import cn.jualn.miniapp.module.comment.vo.ReplyVO;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.post.service.PostService;
import cn.jualn.miniapp.module.user.bo.UserAuthBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Comment service implementation.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class CommentServiceImpl implements CommentService {

    private static final Integer DEFAULT_PAGE_SIZE = 20;
    private static final Integer MAX_PAGE_SIZE = 50;
    private static final Integer PREVIEW_REPLY_COUNT = 2;
    // TODO 如果后续帖子审核策略变化，可将“可评论目标”的判断迁移到各目标模块统一处理。
    // TODO 如果后续评论审核链路复杂，再把审核统计和批量查询迁移到 XML。

    private static final Set<TargetType> ALLOWED_TARGET_TYPES =
            Set.of(TargetType.POST, TargetType.ACTIVITY, TargetType.EXAM);

    private final CommentMapper commentMapper;
    private final CommentConverter commentConverter;
    private final PostService postService;
    private final ActivityService activityService;
    private final ExamService examService;
    private final UserService userService;
    private final TargetValidator targetValidator;
    private final QueueProducer queueProducer;
    private final InteractService interactService;
    private final AuditReservationService auditReservationService;
    private final MediaService mediaService;

    /**
     * Create a comment and update related counts.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createComment(CommentCreateBO command) {
        if (command == null) {
            throw new BusinessException(ResultCode.COMMENT_PARAM_INVALID, "请求参数不能为空");
        }

        Long userId = requireUserId();
        userService.assertContentCreationAllowed(userId);
        TargetType targetType = command.getTargetType();
        long start = System.currentTimeMillis();

        assertTargetType(command.getTargetType());
        targetValidator.assertExists(targetType, command.getTargetId());
//        assertTargetCanReceiveComment(targetType, command.getTargetId());

        Comment parent = null;
        if (command.getParentId() != null) {
            parent = requireParentComment(command.getParentId(), targetType, command.getTargetId());
        }

        String imageUrl = null;
        if (StringUtils.hasText(command.getImageObjectKey())) {
            imageUrl = mediaService.resolveOwnedUploadUrl(TargetType.COMMENT, command.getImageObjectKey());
        } else if (StringUtils.hasText(command.getImageUrl())) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "评论图片缺少合法 objectKey");
        }

        Comment comment = Comment.builder()
                .targetType(targetType.getCode())
                .targetId(command.getTargetId())
                .userId(userId)
                .parentId(command.getParentId())
                .replyToUid(resolveReplyToUid(command.getReplyToUid(), parent))
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
        } else {
            afterCommit(() -> enqueueCommentAudit(comment.getId(), comment.getContent(), reserveResult));
        }

        // 注意：评论审核通过后再增加 commentCount / replyCount，并发送评论/回复通知。
        // 这里不要提前产生对外副作用，避免审核拒绝后计数虚高、通知不可撤回。

        log.info("[CommentService.createComment][完成] userId={}, commentId={}, costMs={}",
                userId, comment.getId(), System.currentTimeMillis() - start);

        return comment.getId();
    }

    /**
     * Page comments with cache.
     *
     * @param query targetId = the targetId of the first-level comment or the target content (post/activity/exam)
     * @return comment list with pagination info
     */
    @Override
    public PageResult<CommentVO> pageComment(CommentPageBO query) {
        assertTargetType(query.getTargetType());
        Long targetId = requireTargetId(query.getTargetId());
        targetValidator.assertExists(query.getTargetType(), targetId);
        int pageSize = normalizePageSize(query.getPageSize());

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .select(Comment::getId, Comment::getContent, Comment::getImageUrl,
                        Comment::getReplyCount, Comment::getCreatedAt, Comment::getUserId)
                .eq(Comment::getTargetType, query.getTargetType().getCode())
                .eq(Comment::getTargetId, targetId)
                .eq(Comment::getStatus, CommentStatus.NORMAL.getCode())
                .isNull(Comment::getParentId)
                .lt(query.getLastId() != null, Comment::getId, query.getLastId())
                .orderByDesc(Comment::getId)
                .last("LIMIT " + pageSize);

        List<Comment> comments = commentMapper.selectList(wrapper);

        Set<Long> commentIds = comments.stream()
                .map(Comment::getId).collect(Collectors.toSet());
        Set<Long> userIds = comments.stream()
                .map(Comment::getUserId)
                .collect(Collectors.toSet());

        Map<Long, UserSimpleBO> userMap = userService.batchGetSimple(userIds);
        Map<Long, Boolean> likedMap = interactService.batchIsLiked(TargetType.COMMENT, commentIds);
        Map<Long, Integer> likeCountMap = interactService.batchGetLikeCount(TargetType.COMMENT, commentIds);
        Map<Long, List<ReplyVO>> previewRepliesMap = batchGetPreviewReplies(commentIds);

        CommentContext context = CommentContext.builder()
                .userMap(userMap).likedMap(likedMap).previewRepliesMap(previewRepliesMap)
                .likeCountMap(likeCountMap).build();

        List<CommentVO> list = commentConverter.toVOList(comments, context);

        PageResult<CommentVO> result = PageResult.of(list, comments.size() == pageSize);
        result.setNextCursor(list.isEmpty() ? null : list.get(list.size() - 1).getId());
        return result;
    }

    /**
     * Page replies for a first-level comment with cache.
     *
     * @param query commentPageBO.targetId = the targetId of the first-level comment (not the reply)
     * @return reply list with pagination info
     */
    @Override
    public PageResult<ReplyVO> pageReply(CommentPageBO query) {
        CommentPageBO actualQuery = query == null ? new CommentPageBO() : query;
        assertTargetType(actualQuery.getTargetType());
        Long targetId = requireTargetId(actualQuery.getTargetId());
        targetValidator.assertExists(actualQuery.getTargetType(), targetId);
        int pageSize = normalizePageSize(actualQuery.getPageSize());

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .select(Comment::getId, Comment::getParentId, Comment::getContent,
                        Comment::getCreatedAt, Comment::getUserId, Comment::getReplyToUid)
                .eq(Comment::getTargetType, actualQuery.getTargetType().getCode())
                .eq(Comment::getTargetId, targetId)
                .eq(Comment::getParentId, actualQuery.getParentId())
                .eq(Comment::getStatus, CommentStatus.NORMAL.getCode())
                .lt(actualQuery.getLastId() != null, Comment::getId, actualQuery.getLastId())
                .orderByDesc(Comment::getId)
                .last("LIMIT " + pageSize);

        List<Comment> comments = commentMapper.selectList(wrapper);

        Set<Long> userIds = comments.stream()
                .map(Comment::getUserId)
                .collect(Collectors.toSet());
        Set<Long> replyUserIds = comments.stream()
                .map(Comment::getReplyToUid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> commentIds = comments.stream()
                .map(Comment::getId).collect(Collectors.toSet());
        Set<Long> allUserIds = new HashSet<>(userIds);
        allUserIds.addAll(replyUserIds);

        Map<Long, UserSimpleBO> userMap = userService.batchGetSimple(allUserIds);
        Map<Long, Boolean> likedMap = interactService.batchIsLiked(TargetType.COMMENT, commentIds);
        Map<Long, Integer> likeCountMap = interactService.batchGetLikeCount(TargetType.COMMENT, commentIds);

        CommentContext context = CommentContext.builder()
                .userMap(userMap).replyUserMap(userMap)
                .likedMap(likedMap).likeCountMap(likeCountMap).build();

        List<ReplyVO> list = commentConverter.toReplyVOList(comments, context);

        PageResult<ReplyVO> result = PageResult.of(list, comments.size() == pageSize);
        result.setNextCursor(list.isEmpty() ? null : list.get(list.size() - 1).getId());
        return result;
    }

    /**
     * Remove comment (soft delete) and adjust counts.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeComment(Long commentId) {
        Long operatorId = requireUserId();

        Comment comment = requireComment(commentId);

        assertCanManageComment(operatorId, comment.getUserId());

        int deleteStatus = Objects.equals(operatorId, comment.getUserId())
                ? CommentStatus.USER_DELETED.getCode()
                : CommentStatus.ADMIN_DELETED.getCode();

        commentMapper.update(new LambdaUpdateWrapper<Comment>()
                .eq(Comment::getId, commentId)
                .set(Comment::getStatus, deleteStatus)
                .set(Comment::getDeletedAt, LocalDateTime.now()));

        boolean shouldDecreaseCount =
                Objects.equals(comment.getStatus(), CommentStatus.NORMAL.getCode());

        if (shouldDecreaseCount && comment.getParentId() != null) {
            commentMapper.decreaseReplyCount(comment.getParentId());
        }

        TargetType targetType = TargetType.fromCode(comment.getTargetType());
        if (shouldDecreaseCount && targetType != null) {
            decreaseTargetCommentCount(targetType, comment.getTargetId());
        }

        log.info("[CommentService.removeComment][完成] operatorId={}, commentId={}", operatorId, commentId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void takeDownComment(AdminCommentActionBO command) {
        validateAdminAction(command);
        AdminCommentStateRow state = commentMapper.selectAdminStateById(command.getCommentId());
        if (state == null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }
        int updated = commentMapper.update(null,
                new LambdaUpdateWrapper<Comment>()
                        .set(Comment::getStatus, CommentStatus.ADMIN_DELETED.getCode())
                        .set(Comment::getDeletedAt, LocalDateTime.now())
                        .eq(Comment::getId, command.getCommentId())
                        .eq(Comment::getStatus, CommentStatus.NORMAL.getCode())
                        .isNull(Comment::getDeletedAt));
        if (updated == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "评论当前状态不允许下架");
        }
        decreaseCommentCounters(state);
        log.info("[CommentService.takeDownComment][完成] operatorId={}, commentId={}, reason={}",
                command.getOperatorId(), command.getCommentId(), command.getReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restoreComment(AdminCommentActionBO command) {
        validateAdminAction(command);
        AdminCommentStateRow state = commentMapper.selectAdminStateById(command.getCommentId());
        if (state == null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }
        if (commentMapper.restoreAdminComment(command.getCommentId()) == 0) {
            throw new BusinessException(ResultCode.DATA_CONFLICT,
                    "只有审核通过且由管理员下架的评论可以恢复");
        }
        increaseCommentCounters(state);
        log.info("[CommentService.restoreComment][完成] operatorId={}, commentId={}, reason={}",
                command.getOperatorId(), command.getCommentId(), command.getReason());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveCommentReview(Long commentId, Long operatorId, String remark) {
        validateReviewAction(commentId, operatorId, false, remark);
        AdminCommentStateRow state = commentMapper.selectAdminStateById(commentId);
        if (state == null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
        }
        if (commentMapper.approveAdminReview(commentId) != 1) {
            throw new BusinessException(ResultCode.DATA_CONFLICT, "评论当前状态不允许通过复核");
        }
        increaseCommentCounters(state);
        log.info("[CommentService.approveCommentReview][完成] operatorId={}, commentId={}, remark={}",
                operatorId, commentId, remark);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectCommentReview(Long commentId, Long operatorId, String reason) {
        validateReviewAction(commentId, operatorId, true, reason);
        if (commentMapper.rejectAdminReview(commentId) != 1) {
            AdminCommentStateRow state = commentMapper.selectAdminStateById(commentId);
            if (state == null) {
                throw new BusinessException(ResultCode.COMMENT_NOT_FOUND);
            }
            throw new BusinessException(ResultCode.DATA_CONFLICT, "评论当前状态不允许拒绝复核");
        }
        log.info("[CommentService.rejectCommentReview][完成] operatorId={}, commentId={}, reason={}",
                operatorId, commentId, reason);
    }

    private void validateReviewAction(Long commentId, Long operatorId, boolean reasonRequired, String reason) {
        if (commentId == null || operatorId == null) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "人工复核参数不完整");
        }
        if (reasonRequired && !StringUtils.hasText(reason)) {
            throw new BusinessException(ResultCode.AUDIT_PARAM_INVALID, "拒绝原因不能为空");
        }
    }

    private void validateAdminAction(AdminCommentActionBO command) {
        if (command == null || command.getOperatorId() == null || command.getCommentId() == null) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "管理操作参数不能为空");
        }
        if (!StringUtils.hasText(command.getReason())) {
            throw new BusinessException(ResultCode.INVALID_OPERATION, "操作原因不能为空");
        }
    }

    private void decreaseCommentCounters(AdminCommentStateRow state) {
        if (state.getParentId() != null) {
            commentMapper.decreaseReplyCount(state.getParentId());
        }
        TargetType targetType = TargetType.fromCode(state.getTargetType());
        if (targetType != null) {
            decreaseTargetCommentCount(targetType, state.getTargetId());
        }
    }

    private void increaseCommentCounters(AdminCommentStateRow state) {
        if (state.getParentId() != null) {
            commentMapper.increaseReplyCount(state.getParentId());
        }
        TargetType targetType = TargetType.fromCode(state.getTargetType());
        if (targetType != null) {
            increaseTargetCommentCount(targetType, state.getTargetId());
        }
    }

    private void afterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private void enqueueCommentAudit(Long commentId, String content, AuditReserveResultBO reserveResult) {
        if (reserveResult == null || !reserveResult.hasAuditTask()) {
            return;
        }

        if (reserveResult.getTextAuditLogId() != null && StringUtils.hasText(content)) {
            queueProducer.send(AuditTextPayload.builder()
                    .auditLogId(reserveResult.getTextAuditLogId())
                    .auditScene(AuditScene.COMMENT)
                    .targetId(commentId)
                    .scene(2)
                    .content(content)
                    .build()
            );
        }

        if (!CollectionUtils.isEmpty(reserveResult.getMediaItems())) {
            List<AuditMediaBatchPayload.AuditMediaItem> items = reserveResult.getMediaItems().stream()
                    .filter(Objects::nonNull)
                    .map(item -> AuditMediaBatchPayload.AuditMediaItem.builder()
                            .auditLogId(item.getAuditLogId())
                            .mediaType(item.getMediaType())
                            .mediaUrl(item.getMediaUrl())
                            .build())
                    .toList();

            if (!items.isEmpty()) {
                queueProducer.send(AuditMediaBatchPayload.builder()
                        .auditScene(AuditScene.COMMENT)
                        .targetId(commentId)
                        .scene(2)
                        .items(items)
                        .build());
            }
        }
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

    /**
     * 批量获取父评论的预览子评论。
     *
     * @param parentIds 父评论 ID 集合
     * @return parentId → 子评论列表（按 id desc）
     */
    private Map<Long, List<ReplyVO>> batchGetPreviewReplies(Collection<Long> parentIds) {
        if (CollectionUtils.isEmpty(parentIds)) return Collections.emptyMap();

        // 1. ROW_NUMBER 取每个 parent 前 N 条
        List<Comment> replies = commentMapper.selectTopNByParentIds(parentIds,
                PREVIEW_REPLY_COUNT);

        if (replies.isEmpty()) return Collections.emptyMap();

        // 2. 收集需要查询的用户 ID（作者 + 被回复者）
        Set<Long> userIds = new HashSet<>();
        replies.forEach(r -> {
            userIds.add(r.getUserId());
            if (r.getReplyToUid() != null) userIds.add(r.getReplyToUid());
        });

        // 3. 批量查询
        Set<Long> replyIds = replies.stream()
                .map(Comment::getId).collect(Collectors.toSet());
        Map<Long, Boolean> likedMap = interactService.batchIsLiked(TargetType.COMMENT, replyIds);
        Map<Long, UserSimpleBO> userMap = userService.batchGetSimple(userIds);
        Map<Long, Integer> likeCountMap = interactService.batchGetLikeCount(TargetType.COMMENT, replyIds);

        CommentContext context = CommentContext.builder()
                .userMap(userMap).replyUserMap(userMap)
                .likedMap(likedMap).likeCountMap(likeCountMap).build();

        // 4. 转 VO，按 parentId 分组
        return replies.stream()
                .map(r -> commentConverter.toReplyVO(r, context))
                .collect(Collectors.groupingBy(ReplyVO::getParentId));
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private Long requireTargetId(Long targetId) {
        if (targetId == null) {
            throw new BusinessException(ResultCode.COMMENT_PARAM_INVALID, "targetId 不能为空");
        }
        return targetId;
    }

    private void assertTargetType(TargetType targetType) {
        if (!ALLOWED_TARGET_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.INVALID_TARGET_TYPE, "targetType 无效");
        }
    }

    // TODO: 查询字段过多，后续考虑减少
    private Comment requireComment(Long commentId) {
        if (commentId == null) {
            throw new BusinessException(ResultCode.COMMENT_PARAM_INVALID, "commentId 不能为空");
        }
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || comment.getDeletedAt() != null) {
            throw new BusinessException(ResultCode.COMMENT_NOT_FOUND, "评论不存在");
        }
        return comment;
    }

    private Comment requireParentComment(Long parentId, TargetType targetType, Long targetId) {
        Comment parent = requireComment(parentId);
        if (parent.getParentId() != null) {
            throw new BusinessException(ResultCode.COMMENT_REPLY_INVALID, "仅支持二级回复");
        }
        if (!Objects.equals(parent.getTargetType(), targetType.getCode())
                || !Objects.equals(parent.getTargetId(), targetId)) {
            throw new BusinessException(ResultCode.COMMENT_REPLY_INVALID, "父评论不属于该目标");
        }
        // TODO 当前仅允许回复审核通过的父评论。
        //      如果后续产品要求可回复短时间 PENDING 父评论，
        //      需要同时处理父评论被拒绝后子回复的隐藏和计数回滚问题。
        if (!Objects.equals(parent.getStatus(), CommentStatus.NORMAL.getCode())) {
            throw new BusinessException(ResultCode.COMMENT_REPLY_INVALID, "父评论不可回复");
        }
        return parent;
    }

    private Long resolveReplyToUid(Long replyToUid, Comment parent) {
        if (parent == null) {
            return null;
        }
        return replyToUid != null ? replyToUid : parent.getUserId();
    }

    private int normalizePageSize(Integer pageSize) {
        int actual = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (actual < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(actual, MAX_PAGE_SIZE);
    }

    private void decreaseTargetCommentCount(TargetType type, Long targetId) {
        switch (type) {
            case POST -> postService.decreaseCommentCount(targetId);
            case ACTIVITY -> activityService.decreaseCommentCount(targetId);
            case EXAM -> examService.decreaseCommentCount(targetId);
        }
    }

    private void increaseTargetCommentCount(TargetType type, Long targetId) {
        switch (type) {
            case POST -> postService.increaseCommentCount(targetId);
            case ACTIVITY -> activityService.increaseCommentCount(targetId);
            case EXAM -> examService.increaseCommentCount(targetId);
        }
    }

    private void assertCanManageComment(Long operatorId, Long authorId) {
        if (Objects.equals(operatorId, authorId)) {
            return;
        }
        UserAuthBO auth = userService.getUserAuthInfo(operatorId);
        if (auth != null && (auth.getRole() == UserRole.OPR || auth.getRole() == UserRole.ADMIN)) {
            return;
        }
        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    @Data
    @Builder
    public static class CommentContext {
        private Map<Long, UserSimpleBO> userMap;
        private Map<Long, UserSimpleBO> replyUserMap;
        private Map<Long, Boolean> likedMap;
        private Map<Long, Integer> likeCountMap;
        private Map<Long, List<ReplyVO>> previewRepliesMap;
    }
}
