package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.interact.service.InteractionCounts;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.interact.bo.UserLikeBO;
import cn.jualn.miniapp.module.interact.dto.inner.UserLikeQuery;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.service.MediaQueries;
import cn.jualn.miniapp.module.post.PostProblems;
import cn.jualn.miniapp.module.post.bo.*;
import cn.jualn.miniapp.module.post.converter.PostConverter;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.enums.PostStatus;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PostQueries {
    private static final int DEFAULT_PAGE_SIZE = 20, MAX_PAGE_SIZE = 50;
    private static final long PENDING_AUDIT_VISIBLE_MINUTES = PostPublicAccess.PENDING_VISIBLE_MINUTES;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<MentorQuestions> mentorQuestions;
    private final CurrentAuthorization authorization;
    private final PostOrganizationService organization;
    private final PostCommentPermissions permissions;
    private final PostCursorCodec cursors;
    private final PostMapper postMapper;
    private final PostConverter postConverter;
    private final MediaQueries mediaService;
    private final UserService userService;
    private final InteractionCounts interactionCounts;
    private final InteractionLikeQueries interactionLikeQueries;

    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PageResult<PostListBO> pagePost(PostFeedQueryBO query) {
        PostFeedQueryBO actualQuery = query == null ? new PostFeedQueryBO() : query;
        int pageSize = normalizePageSize(actualQuery.getPageSize());

        String fingerprint = feedFingerprint(actualQuery);
        organization.validateFilter(actualQuery.getCategoryId(), actualQuery.getTopicId());
        if("MENTOR_QA".equals(actualQuery.getPostType()))mentorQuestions.getObject().requireKnownFilter(actualQuery);
        if (actualQuery.getLastId() != null && (actualQuery.getCursor() != null || !"NORMAL".equals(actualQuery.getPostType()) || actualQuery.getCategoryId() != null || actualQuery.getTopicId() != null)) {
            throw cn.jualn.miniapp.module.post.PostProblems.invalid("lastId", "INVALID_VALUE", "Use a query-bound cursor for filtered feeds");
        }
        var anchor = cursors.decode(actualQuery.getCursor(), fingerprint);
        Long originalLast = actualQuery.getLastId();
        List<Post> posts;
        try {
            if (anchor != null) {
                actualQuery.setLastId(anchor.id());
            }
            posts = postMapper.pageOrganizedPosts(new cn.jualn.miniapp.module.post.bo.PostFilterBO(actualQuery.getPostType(), actualQuery.getCategoryId(), actualQuery.getTopicId(), actualQuery.getUserId(), actualQuery.getKeyword(), resolveListStatus(actualQuery.getStatus()), actualQuery.getLastId(), LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).minusMinutes(PENDING_AUDIT_VISIBLE_MINUTES), pageSize,actualQuery.getAnswerState()));
        } finally {
            actualQuery.setLastId(originalLast);
        }

        List<PostListBO> result = enrichPosts(posts);

        boolean more = posts.size() == pageSize;
        Long next = posts.isEmpty() ? null : posts.get(posts.size() - 1).getId();
        return new cn.jualn.miniapp.module.post.bo.PostFeedPageBO(result, more, next, more ? cursors.encode(fingerprint, next, 0) : null);
    }

    public PageResult<PostListBO> pageUserLikedPosts(Long userId, Long lastLikeId, Integer pageSize) {
        return pageUserLikedPosts(userId, lastLikeId, pageSize, "NORMAL");
    }

    public PageResult<PostListBO> pageUserLikedPosts(Long userId, Long lastLikeId, Integer pageSize, String postType) {
        checkTypeFilter(postType);
        int size = normalizePageSize(pageSize);

        // 第一跳：从 likes 表分页，游标是 likeId（不是 postId）
        List<UserLikeBO> likeRecords = interactionLikeQueries.pageUserLikes(
                UserLikeQuery.builder()
                        .userId(userId).pageSize(size).lastId(lastLikeId)
                        .targetType(TargetType.POST).build()
        );
        if (likeRecords.isEmpty()) {
            return PageResult.empty();
        }

        // 保留顺序：用户点赞的时间顺序
        List<Long> postIds = likeRecords.stream()
                .map(UserLikeBO::getTargetId)
                .toList();

        // 第二跳：按 postId 批量查帖子
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .select(Post::getId, Post::getUserId, Post::getTitle,
                        Post::getContent, Post::getCommentCount, Post::getPublishedAt, Post::getPostType, Post::getCategoryId)
                .eq(!"ALL".equals(postType), Post::getPostType, postType)
                .in(Post::getId, postIds)
                .eq(Post::getStatus, PostStatus.PUBLISHED.getCode());

        applyPublicAuditVisibleCondition(wrapper);

        List<Post> posts = postMapper.selectList(wrapper);

        // 按 likeRecords 顺序排列（selectBatchIds 不保证顺序）
        Map<Long, Post> postMap = posts.stream()
                .collect(Collectors.toMap(Post::getId, p -> p));
        List<Post> orderedPosts = postIds.stream()
                .map(postMap::get)
                .filter(Objects::nonNull)
                .toList();

        // 复用组装逻辑
        List<PostListBO> result = enrichPosts(orderedPosts);

        // ⚠️ 游标用 likeRecord 的 id，不是 postId
        PageResult<PostListBO> pageResult = PageResult.of(result, likeRecords.size() == size);
        pageResult.setNextCursor(likeRecords.get(likeRecords.size() - 1).getId());
        return pageResult;
    }

    public PageResult<PostListBO> searchPosts(String keyword, Long lastId, Integer pageSize) {
        PostFeedQueryBO query = new PostFeedQueryBO();
        query.setKeyword(keyword);
        query.setLastId(lastId);
        query.setPageSize(pageSize);
        query.setStatus(PostStatus.PUBLISHED.getCode());
        return pagePost(query);
    }

    @org.springframework.transaction.annotation.Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PostDetailBO getPostDetail(Long postId) {
        if (postId == null) {
            throw new BusinessException(ResultCode.POST_PARAM_INVALID, "postId 不能为空");
        }

        Post post = requireExistingPost(postId);

        UserSimpleBO author = userService.getSimpleInfo(post.getUserId());
        PostDetailBO detailBO = postConverter.toDetailBO(post);
        detailBO.setAuthor(author);
        detailBO.setAttachments(mediaService.listSimpleAttachments(TargetType.POST, postId));
        var projection = organization.project(List.of(postId), post.getCategoryId() == null ? List.of() : List.of(post.getCategoryId()));
        detailBO.setCategory(post.getCategoryId() == null ? null : projection.categories().get(post.getCategoryId()));
        detailBO.setTopics(projection.topics().getOrDefault(postId, List.of()));
        detailBO.setCommentPermission(permissions.answer(UserContext.getUserId(), post.getPostType()));
        if("MENTOR_QA".equals(post.getPostType()))detailBO.setQaSummary(mentorQuestions.getObject().summary(postId));
        detailBO.setLiked(interactionLikeQueries.isLiked(TargetType.POST, postId));
        if("NORMAL".equals(post.getPostType())) detailBO.setCommentCount(postMapper.communityCommentCounts(List.of(postId)).stream().findFirst().map(PostDiscussionCount::count).orElse(0));
        return detailBO;
    }

    private void applyPublicAuditVisibleCondition(LambdaQueryWrapper<Post> wrapper) {
        PostPublicAccess.publicAuditCondition(wrapper);
    }

    private List<PostListBO> enrichPosts(List<Post> posts) {
        Set<Long> postIds = posts.stream().map(Post::getId).collect(Collectors.toSet());

        Map<Long, UserSimpleBO> userMap = userService.batchGetSimple(
                posts.stream().map(Post::getUserId).toList());
        Map<Long, List<MediaAttachmentSimpleBO>> attachmentMap =
                mediaService.batchListSimpleAttachments(TargetType.POST, postIds);
        Map<Long, Boolean> likedMap =
                interactionLikeQueries.batchIsLiked(TargetType.POST, postIds);
        Map<Long, Integer> likeCountMap =
                interactionCounts.batchGetLikeCount(TargetType.POST, postIds);
        Map<Long, Integer> viewCountMap =
                interactionCounts.batchGetViewCount(TargetType.POST, postIds);

        PostListContext context = PostListContext.builder()
                .userMap(userMap).likedMap(likedMap)
                .likeCountMap(likeCountMap).viewCountMap(viewCountMap)
                .attachmentMap(attachmentMap).build();
        var result = postConverter.toListBOList(posts, context);
        enrichOrganization(posts, result);
        var normalIds=posts.stream().filter(p->"NORMAL".equals(p.getPostType())).map(Post::getId).toList();
        if(!normalIds.isEmpty()) {var counts=postMapper.communityCommentCounts(normalIds).stream().collect(Collectors.toMap(PostDiscussionCount::postId,PostDiscussionCount::count));for(var item:result)if("NORMAL".equals(item.getPostType()))item.setCommentCount(counts.getOrDefault(item.getId(),0));}
        return result;
    }

    private void checkTypeFilter(String type) {
        if (type == null || !Set.of("NORMAL", "MENTOR_QA", "ALL").contains(type)) {
            throw PostProblems.invalid("postType", "INVALID_VALUE", "Unsupported type filter");
        }
    }

    private String feedFingerprint(PostFeedQueryBO q) {
        checkTypeFilter(q.getPostType());
        if(q.getAnswerState()!=null && (!"MENTOR_QA".equals(q.getPostType()) || !Set.of("ALL","ANSWERED","UNANSWERED").contains(q.getAnswerState())))throw PostProblems.invalid("answerState","INVALID_VALUE","Answer filtering requires an explicit mentor question type");
        if (q.getStatus() != null && (q.getStatus() < 0 || q.getStatus() > 4)) {
            throw PostProblems.invalid("status", "INVALID_VALUE", "Unsupported status");
        }
        // Length-prefixed keyword prevents separator collisions with other predicates.
        String keyword = q.getKeyword() == null ? "" : q.getKeyword();
        return "feed:"+Objects.toString(q.getAnswerState(),"ALL")+":" + q.getPostType() + ":" + q.getCategoryId() + ":" + q.getTopicId() + ":" + q.getUserId() + ":" + resolveListStatus(q.getStatus()) + ":" + keyword.length() + ":" + keyword;
    }

    private void enrichOrganization(List<Post> posts, List<PostListBO> rows) {
        var projection = organization.project(posts.stream().map(Post::getId).toList(), posts.stream().map(Post::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<String, PostWritePermission> permissionMap = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            var post = posts.get(i);
            row.setPostType(post.getPostType());
            if("MENTOR_QA".equals(post.getPostType()))row.setQaSummary(mentorQuestions.getObject().summary(post.getId()));
            row.setCategory(post.getCategoryId() == null ? null : projection.categories().get(post.getCategoryId()));
            row.setTopics(projection.topics().getOrDefault(post.getId(), List.of()));
            row.setCommentPermission(permissionMap.computeIfAbsent(post.getPostType(), type -> permissions.answer(UserContext.getUserId(), type)));
        }
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private int normalizePageSize(Integer pageSize) {
        int actualPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        if (actualPageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(actualPageSize, MAX_PAGE_SIZE);
    }

    private Integer resolveListStatus(Integer requestedStatus) {
        if (requestedStatus == null) {
            return PostStatus.PUBLISHED.getCode();
        }
        if (requestedStatus.equals(PostStatus.PUBLISHED.getCode())) {
            return requestedStatus;
        }
        requireOperatorOrAdmin();
        return requestedStatus;
    }

    private void requireOperatorOrAdmin() {
        authorization.requireLegacyEntry(requireUserId(), "content:read", "POST", "content:read");
    }

    private Post requireExistingPost(Long postId) {
        if (postId == null) {
            throw new BusinessException(ResultCode.POST_PARAM_INVALID, "postId 不能为空");
        }

        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .select(Post::getId, Post::getUserId, Post::getTitle, Post::getContent,
                        Post::getViewCount, Post::getLikeCount, Post::getCommentCount,
                        Post::getPublishedAt, Post::getAuditStatus, Post::getPostType, Post::getCategoryId)
                .eq(Post::getStatus, PostStatus.PUBLISHED.getCode())
                .eq(Post::getId, postId);

        applyPublicAuditVisibleCondition(wrapper);

        Post post = postMapper.selectOne(wrapper);

        if (post == null) {
            throw new BusinessException(ResultCode.POST_NOT_FOUND, "帖子不存在");
        }

        return post;
    }
}
