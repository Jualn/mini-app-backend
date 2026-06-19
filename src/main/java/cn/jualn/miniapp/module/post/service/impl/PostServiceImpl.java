package cn.jualn.miniapp.module.post.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.payload.AuditMediaBatchPayload;
import cn.jualn.miniapp.module.audit.payload.AuditTextPayload;
import cn.jualn.miniapp.module.interact.bo.UserLikeBO;
import cn.jualn.miniapp.module.interact.dto.inner.UserLikeQuery;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.post.bo.PostCreateBO;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.converter.PostConverter;
import cn.jualn.miniapp.module.post.dto.request.PostPageQuery;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.common.enums.PostStatus;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.service.PostService;
import cn.jualn.miniapp.module.post.bo.PostDetailBO;
import cn.jualn.miniapp.module.post.vo.PostDetailVO;
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
 * 帖子服务实现。
 *
 * <p>负责帖子创建、分页、详情、删除以及与互动模块共享的点赞计数更新。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private static final Integer DEFAULT_PAGE_SIZE = 20;
    private static final Integer MAX_PAGE_SIZE = 50;

    private final PostMapper postMapper;
    private final PostConverter postConverter;
    private final MediaService mediaService;
    private final UserService userService;
    private final RedisService redisService;
    private final QueueProducer queueProducer;
    private final InteractService interactService;

    /**
     * 创建帖子并按需写入图片附件。
     *
     * <p>该方法在事务内执行，保证帖子主数据与附件关联的一致性。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PostListBO createPost(PostCreateBO command) {
        if (command == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请求参数不能为空");
        }
        Long userId = requireUserId();
        long start = System.currentTimeMillis();
        log.info("[PostService.createPost][开始] userId={}, title={}", userId, command.getTitle());


        Post post = Post.builder()
                .userId(userId)
                .title(command.getTitle())
                .content(command.getContent())
                .status(PostStatus.PUBLISHED.getCode())
                .publishedAt(LocalDateTime.now())
                .build();

        postMapper.insert(post);

        if (!CollectionUtils.isEmpty(command.getAttachmentItems())) {
            mediaService.replaceAttachments(MediaAttachmentSaveBO.builder()
                    .targetType(TargetType.POST)
                    .targetId(post.getId())
                    .attachments(command.getAttachmentItems())
                    .build());
        }

        UserSimpleBO author = userService.getSimpleInfo(userId);
        List<MediaAttachmentSimpleBO> attachments = buildSimpleAttachments(post.getId(), command.getAttachmentItems());

        afterCommit(() -> enqueueAudit(post.getId(), post.getContent(), command.getAttachmentItems()));

        log.info("[PostService.createPost][完成] userId={}, postId={}, costMs={}", userId, post.getId(), System.currentTimeMillis() - start);

        return PostListBO.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .likeCount(0)
                .commentCount(0)
                .viewCount(0)
                .publishedAt(LocalDateTime.now())
                .author(author)
                .attachments(attachments)
                .liked(false)
                .build();
    }

    /**
     * 游标分页查询帖子列表。
     *
     * <p>优先读 Redis 短缓存，未命中再回源 DB 并回填；
     * 列表缓存只做短 TTL，不做大范围主动清理，适配低规格 ECS 资源。</p>
     */
    @Override
    public PageResult<PostListBO> pagePost(PostPageQuery query) {
        PostPageQuery actualQuery = query == null ? new PostPageQuery() : query;
        int pageSize = normalizePageSize(actualQuery.getPageSize());

        List<Post> posts = postMapper.selectList(buildWrapper(actualQuery, pageSize));

        List<PostListBO> result = enrichPosts(posts);

        PageResult<PostListBO> pageResult = PageResult.of(result, posts.size() == pageSize);
        pageResult.setNextCursor(result.isEmpty() ? null : result.get(result.size() - 1).getId());
        return pageResult;
    }

    /**
     * 分页查询用户点赞的帖子列表。
     *
     * @param userId 用户 ID
     * @param lastLikeId 上一页最后一个点赞记录 ID，首次查询可为空
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @Override
    public PageResult<PostListBO> pageUserLikedPosts(Long userId, Long lastLikeId, Integer pageSize) {
        int size = normalizePageSize(pageSize);

        // 第一跳：从 likes 表分页，游标是 likeId（不是 postId）
        List<UserLikeBO> likeRecords = interactService.pageUserLikes(
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
        List<Post> posts = postMapper.selectList(
                new LambdaQueryWrapper<Post>()
                        .select(Post::getId, Post::getUserId, Post::getTitle,
                                Post::getContent, Post::getCommentCount, Post::getPublishedAt)
                        .in(Post::getId, postIds)
                        .eq(Post::getStatus, PostStatus.PUBLISHED.getCode())
        );

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

    /**
     * 搜索帖子（全文索引）。
     *
     * @param keyword 搜索关键词
     * @param lastId 上一页最后一个帖子 ID，首次查询可为空
     * @param pageSize 每页条数
     * @return 分页结果
     */
    @Override
    public PageResult<PostListBO> searchPosts(String keyword, Long lastId, Integer pageSize) {
        PostPageQuery query = new PostPageQuery();
        query.setKeyword(keyword);
        query.setLastId(lastId);
        query.setPageSize(pageSize);
        query.setStatus(PostStatus.PUBLISHED.getCode());
        return pagePost(query);
    }

    /**
     * 查询帖子详情。
     *
     * <p>读路径：详情缓存 -> DB；不存在帖子会缓存空值占位，降低穿透风险。</p>
     */
    @Override
    public PostDetailVO getPostDetail(Long postId) {
        if (postId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "postId 不能为空");
        }

        String detailCacheKey = buildPostDetailCacheKey(postId);

        PostDetailBO detailBO;

        PostDetailBO cachedDetail = redisService.get(detailCacheKey, PostDetailBO.class);
        if (cachedDetail != null) {
            detailBO = cachedDetail;
        } else {
            Post post = requireExistingPost(postId);

            UserSimpleBO author = userService.getSimpleInfo(post.getUserId());
            List<MediaAttachmentSimpleBO> attachments = mediaService.listSimpleAttachments(TargetType.POST, postId);

            detailBO = postConverter.toDetailBO(post);
            detailBO.setAuthor(author);
            detailBO.setAttachments(attachments);

            redisService.set(detailCacheKey, detailBO, RedisKeyConstant.POST_DETAIL_TTL);
        }

        PostDetailVO detailVO = postConverter.toDetailVO(detailBO);
        detailVO.setLiked(interactService.isLiked(TargetType.POST, postId));

        return detailVO;
    }

    /**
     * 软删除帖子，并清理详情缓存。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removePost(Long postId) {
        Long operatorId = requireUserId();
        Long postAuthorId = postMapper.selectUserIdById(postId);
        if (postAuthorId == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "帖子不存在");
        }
        assertCanManagePost(operatorId, postAuthorId);

        postMapper.update(
                new LambdaUpdateWrapper<Post>()
                        .set(Post::getStatus, PostStatus.DELETED.getCode())
                        .set(Post::getDeletedAt, LocalDateTime.now())
                        .eq(Post::getId, postId)
        );
        redisService.delete(buildPostDetailCacheKey(postId));
//        afterCommit(() -> searchService.removeByTarget(TargetType.POST, postId));
        log.info("[PostService.removePost][完成] operatorId={}, postId={}", operatorId, postId);
    }

    @Override
    public void increaseCommentCount(Long postId) {
        postMapper.incrementCommentCount(postId);
        redisService.delete(buildPostDetailCacheKey(postId));
    }

    @Override
    public void decreaseCommentCount(Long postId) {
        postMapper.decrementCommentCount(postId);
        redisService.delete(buildPostDetailCacheKey(postId));
    }

    private List<PostListBO> enrichPosts(List<Post> posts) {
        Set<Long> postIds = posts.stream().map(Post::getId).collect(Collectors.toSet());

        Map<Long, UserSimpleBO> userMap = userService.batchGetSimple(
                posts.stream().map(Post::getUserId).toList());
        Map<Long, List<MediaAttachmentSimpleBO>> attachmentMap =
                mediaService.batchListSimpleAttachments(TargetType.POST, postIds);
        Map<Long, Boolean> likedMap =
                interactService.batchIsLiked(TargetType.POST, postIds);
        Map<Long, Integer> likeCountMap =
                interactService.batchGetLikeCount(TargetType.POST, postIds);
        Map<Long, Integer> viewCountMap =
                interactService.batchGetViewCount(TargetType.POST, postIds);

        PostContext context = PostContext.builder()
                .userMap(userMap).likedMap(likedMap)
                .likeCountMap(likeCountMap).viewCountMap(viewCountMap)
                .attachmentMap(attachmentMap).build();
        return postConverter.toListBOList(posts, context);
    }

    private LambdaQueryWrapper<Post> buildWrapper(PostPageQuery query, int pageSize) {
        return new LambdaQueryWrapper<Post>()
                .select(Post::getId, Post::getUserId, Post::getTitle, Post::getContent,
                        Post::getCommentCount, Post::getPublishedAt)
                .eq(Post::getStatus, resolveListStatus(query.getStatus()))
                // 按用户过滤（新增）
                .eq(query.getUserId() != null, Post::getUserId, query.getUserId())
                // 全文搜索, 有 keyword 时走 FULLTEXT，无 keyword 走原逻辑
                .apply(StringUtils.hasText(query.getKeyword()),
                        "MATCH(content) AGAINST({0} IN BOOLEAN MODE)",
                        query.getKeyword())
                .lt(query.getLastId() != null, Post::getId, query.getLastId())
                .orderByDesc(Post::getId)
                .last("LIMIT " + pageSize);
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private void enqueueAudit(Long postId, String content, List<AttachmentItemBO> attachmentItems) {
        if (!CollectionUtils.isEmpty(attachmentItems)) {
            List<AuditMediaBatchPayload.AuditMediaItem> items = attachmentItems.stream()
                    .filter(Objects::nonNull)
                    .map(item -> AuditMediaBatchPayload.AuditMediaItem.builder()
                            .mediaType(item.getType())
                            .mediaUrl(item.getUrl())
                            .build())
                    .toList();
            if (!items.isEmpty()) {
                queueProducer.send(AuditMediaBatchPayload.builder()
                        .targetType(TargetType.POST)
                        .targetId(postId)
                        .scene(3)
                        .items(items)
                        .build());
            }
        }

        queueProducer.send(
                AuditTextPayload.builder()
                        .targetId(postId)
                        .targetType(TargetType.POST)
                        .content(content)
                        .scene(3)
                        .build()
        );
    }

    private List<MediaAttachmentSimpleBO> buildSimpleAttachments(Long postId, List<AttachmentItemBO> attachmentItems) {
        if (CollectionUtils.isEmpty(attachmentItems)) {
            return List.of();
        }
        return attachmentItems.stream()
                .filter(Objects::nonNull)
                .map(item -> MediaAttachmentSimpleBO.builder()
                        .targetId(postId)
                        .url(item.getUrl())
                        .sortOrder(item.getSortOrder())
                        .build())
                .toList();
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
        UserAuthBO currentProfile = userService.getUserAuthInfo(requireUserId());
        UserRole userRole = currentProfile.getRole();
        if (userRole != UserRole.OPR && userRole != UserRole.ADMIN) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    private Post requireExistingPost(Long postId) {
        if (postId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "postId 不能为空");
        }
        Post post = postMapper.selectOne(new LambdaQueryWrapper<Post>()
                .select(Post::getId, Post::getUserId, Post::getTitle, Post::getContent,
                        Post::getViewCount, Post::getLikeCount, Post::getCommentCount,
                        Post::getPublishedAt)
                .eq(Post::getStatus, PostStatus.PUBLISHED.getCode())
                .eq(Post::getAuditStatus, AuditStatus.PASS.getCode())
                .eq(Post::getId, postId));
        if (post == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "帖子不存在");
        }
        return post;
    }

    private void assertCanManagePost(Long operatorId, Long postAuthorId) {
        if (Objects.equals(operatorId, postAuthorId)) {
            return;
        }

        UserAuthBO currentProfile = userService.getUserAuthInfo(operatorId);
        if (currentProfile.getRole() == UserRole.OPR || currentProfile.getRole() == UserRole.ADMIN) {
            return;
        }

        throw new BusinessException(ResultCode.FORBIDDEN);
    }

    private String buildPostDetailCacheKey(Long postId) {
        return RedisKeyConstant.postDetail(postId);
    }

    @Data
    @Builder
    public static class PostContext {
        private Map<Long, UserSimpleBO> userMap;
        private Map<Long, Boolean> likedMap;
        private Map<Long, Integer> likeCountMap;
        private Map<Long, Integer> viewCountMap;
        private Map<Long, List<MediaAttachmentSimpleBO>> attachmentMap;
    }

}
