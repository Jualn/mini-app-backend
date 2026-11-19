package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.interact.service.InteractionCounts;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.comment.bo.CommentPageBO;
import cn.jualn.miniapp.module.comment.converter.CommentConverter;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.enums.CommentStatus;
import cn.jualn.miniapp.module.comment.mapper.CommentMapper;
import cn.jualn.miniapp.module.post.service.PostPublicAccess;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.stream.Collectors;

import cn.jualn.miniapp.module.comment.bo.CommentItemBO;
import cn.jualn.miniapp.module.comment.bo.ReplyItemBO;
import cn.jualn.miniapp.module.comment.bo.CommentReadContext;

@Service
@RequiredArgsConstructor
public class CommentQueries {
    private static final int DEFAULT_PAGE_SIZE = 20, MAX_PAGE_SIZE = 50, PREVIEW_REPLY_COUNT = 2;
    private static final Set<TargetType> ALLOWED_TARGET_TYPES = Set.of(TargetType.POST, TargetType.ACTIVITY, TargetType.EXAM);
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<MentorAnswers> mentorAnswers;
    private final PostQaCommentPolicy qaPolicy;
    private final CommentMapper commentMapper;
    private final CommentConverter commentConverter;
    private final PostPublicAccess postAccess;
    private final UserService userService;
    private final CommentTargetAccess targetAccess;
    private final InteractionCounts interactionCounts;
    private final InteractionLikeQueries interactionLikeQueries;

    public PageResult<CommentItemBO> pageComment(CommentPageBO query) {
        assertTargetType(query.getTargetType());
        Long targetId = requireTargetId(query.getTargetId());
        targetAccess.requireSubject(query.getTargetType(), targetId, false);
        var subjectAccess = query.getTargetType() == TargetType.POST ? postAccess.require(targetId, false) : null;
        int pageSize = normalizePageSize(query.getPageSize());

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .select(Comment::getId, Comment::getContent, Comment::getImageUrl,
                        Comment::getReplyCount, Comment::getCreatedAt, Comment::getUserId, Comment::getMentorEvidence)
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
        Map<Long, Boolean> likedMap = interactionLikeQueries.batchIsLiked(TargetType.COMMENT, commentIds);
        Map<Long, Integer> likeCountMap = interactionCounts.batchGetLikeCount(TargetType.COMMENT, commentIds);
        Map<Long, List<ReplyItemBO>> previewRepliesMap = batchGetPreviewReplies(commentIds, subjectAccess);

        CommentReadContext context = CommentReadContext.builder()
                .userMap(userMap).likedMap(likedMap).previewRepliesMap(previewRepliesMap)
                .likeCountMap(likeCountMap).build();

        List<CommentItemBO> list = commentConverter.toItemBOList(comments, context);
        var replyPermissions = qaPolicy.replies(subjectAccess, UserContext.getUserId(), comments);
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setReplyPermission(replyPermissions.get(comments.get(i).getId()));
            if(subjectAccess!=null && "MENTOR_QA".equals(subjectAccess.postType())){list.get(i).setKind("MENTOR_ANSWER");list.get(i).setMentorAnswer(mentorAnswers.getObject().attribution(comments.get(i).getId()));}
        }

        PageResult<CommentItemBO> result = PageResult.of(list, comments.size() == pageSize);
        result.setNextCursor(list.isEmpty() ? null : list.get(list.size() - 1).getId());
        return result;
    }

    public PageResult<ReplyItemBO> pageReply(CommentPageBO query) {
        CommentPageBO actualQuery = query == null ? new CommentPageBO() : query;
        assertTargetType(actualQuery.getTargetType());
        Long targetId = requireTargetId(actualQuery.getTargetId());
        targetAccess.requireSubject(actualQuery.getTargetType(), targetId, false);
        var subjectAccess = actualQuery.getTargetType() == TargetType.POST ? postAccess.require(targetId, false) : null;
        targetAccess.requireReplyRoot(actualQuery.getParentId(), actualQuery.getTargetType(), targetId);
        int pageSize = normalizePageSize(actualQuery.getPageSize());

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<Comment>()
                .select(Comment::getId, Comment::getParentId, Comment::getContent,
                        Comment::getCreatedAt, Comment::getUserId, Comment::getReplyToUid, Comment::getReplyToCommentId, Comment::getMentorEvidence)
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
        Map<Long, Boolean> likedMap = interactionLikeQueries.batchIsLiked(TargetType.COMMENT, commentIds);
        Map<Long, Integer> likeCountMap = interactionCounts.batchGetLikeCount(TargetType.COMMENT, commentIds);

        CommentReadContext context = CommentReadContext.builder()
                .userMap(userMap).replyUserMap(userMap)
                .likedMap(likedMap).likeCountMap(likeCountMap).build();

        List<ReplyItemBO> list = commentConverter.toReplyItemBOList(comments, context);
        var replyPermissions = qaPolicy.replies(subjectAccess, UserContext.getUserId(), comments);
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setReplyPermission(replyPermissions.get(comments.get(i).getId()));
            if(subjectAccess!=null && "MENTOR_QA".equals(subjectAccess.postType()))list.get(i).setKind("DISCUSSION");
        }

        PageResult<ReplyItemBO> result = PageResult.of(list, comments.size() == pageSize);
        result.setNextCursor(list.isEmpty() ? null : list.get(list.size() - 1).getId());
        return result;
    }

    private Map<Long, List<ReplyItemBO>> batchGetPreviewReplies(Collection<Long> parentIds, cn.jualn.miniapp.module.post.bo.PostCommentAccessBO postAccess) {
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
        Map<Long, Boolean> likedMap = interactionLikeQueries.batchIsLiked(TargetType.COMMENT, replyIds);
        Map<Long, UserSimpleBO> userMap = userService.batchGetSimple(userIds);
        Map<Long, Integer> likeCountMap = interactionCounts.batchGetLikeCount(TargetType.COMMENT, replyIds);

        CommentReadContext context = CommentReadContext.builder()
                .userMap(userMap).replyUserMap(userMap)
                .likedMap(likedMap).likeCountMap(likeCountMap).build();

        // 4. 转 VO，按 parentId 分组
        var replyPermissions = qaPolicy.replies(postAccess, UserContext.getUserId(), replies);
        return replies.stream()
                .map(r -> {
                    var value = commentConverter.toReplyItemBO(r, context);
                    value.setReplyPermission(replyPermissions.get(r.getId()));
                    if(postAccess!=null && "MENTOR_QA".equals(postAccess.postType()))value.setKind("DISCUSSION");
                    return value;
                })
                .collect(Collectors.groupingBy(ReplyItemBO::getParentId));
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

    private int normalizePageSize(Integer pageSize) {
        int actual = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (actual < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(actual, MAX_PAGE_SIZE);
    }
}
