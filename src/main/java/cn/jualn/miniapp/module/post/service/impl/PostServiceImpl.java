package cn.jualn.miniapp.module.post.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.payload.AuditMediaBatchPayload;
import cn.jualn.miniapp.module.audit.payload.AuditTextPayload;
import cn.jualn.miniapp.module.audit.service.AuditService;
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
    // TODO 如果后续发现 PENDING 帖子积压较多，
    //      再增加定时任务扫描超时 PENDING 帖子并下架或迁移到 PostMapper.xml 优化查询。
    private static final long PENDING_AUDIT_VISIBLE_MINUTES = 10;


    private final PostMapper postMapper;
    private final PostConverter postConverter;
    private final MediaService mediaService;
    private final UserService userService;
    private final RedisService redisService;
    private final QueueProducer queueProducer;
    private final InteractService interactService;
    private final AuditService auditService;

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
                // 乐观发布：创建后先可见
                .status(PostStatus.PUBLISHED.getCode())
                // 默认先置为 PENDING，后面根据是否真的有审核任务修正
                .auditStatus(AuditStatus.PENDING.getCode())
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

        AuditReserveResultBO reserveResult = auditService.reserveAuditLogs(
                AuditReserveBO.builder()
                        .auditScene(AuditScene.POST)
                        .targetId(post.getId())
                        .textContent(post.getContent())
                        .mediaItems(buildAuditReserveMediaItems(command.getAttachmentItems()))
                        .build()
        );

        if (!reserveResult.hasAuditTask()) {
            postMapper.update(
                    new LambdaUpdateWrapper<Post>()
                            .set(Post::getAuditStatus, AuditStatus.PASS.getCode())
                            .eq(Post::getId, post.getId())
            );
        }

        UserSimpleBO author = userService.getSimpleInfo(userId);
        List<MediaAttachmentSimpleBO> attachments =
                buildSimpleAttachments(post.getId(), command.getAttachmentItems());

        afterCommit(() -> enqueueAudit(post.getId(), post.getContent(), reserveResult));

        log.info("[PostService.createPost][完成] userId={}, postId={}, costMs={}",
                userId, post.getId(), System.currentTimeMillis() - start);

        return PostListBO.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .likeCount(0)
                .commentCount(0)
                .viewCount(0)
                .publishedAt(post.getPublishedAt())
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
     * @param userId     用户 ID
     * @param lastLikeId 上一页最后一个点赞记录 ID，首次查询可为空
     * @param pageSize   每页条数
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
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .select(Post::getId, Post::getUserId, Post::getTitle,
                        Post::getContent, Post::getCommentCount, Post::getPublishedAt)
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

    /**
     * 搜索帖子（全文索引）。
     *
     * @param keyword  搜索关键词
     * @param lastId   上一页最后一个帖子 ID，首次查询可为空
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

        Post post = requireExistingPost(postId);

        String detailCacheKey = buildPostDetailCacheKey(postId);
        PostDetailBO detailBO = null;

        // 只有审核通过的帖子才读缓存，避免 PENDING 过期后仍从缓存展示。
        if (Objects.equals(post.getAuditStatus(), AuditStatus.PASS.getCode())) {
            detailBO = redisService.get(detailCacheKey, PostDetailBO.class);
        }

        if (detailBO == null) {
            UserSimpleBO author = userService.getSimpleInfo(post.getUserId());
            List<MediaAttachmentSimpleBO> attachments =
                    mediaService.listSimpleAttachments(TargetType.POST, postId);

            detailBO = postConverter.toDetailBO(post);
            detailBO.setAuthor(author);
            detailBO.setAttachments(attachments);

            // 只有 PASS 帖子才缓存。PENDING 只是短期乐观展示，不缓存。
            if (Objects.equals(post.getAuditStatus(), AuditStatus.PASS.getCode())) {
                redisService.set(detailCacheKey, detailBO, RedisKeyConstant.POST_DETAIL_TTL);
            }
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

    /**
     * 公开展示规则：
     * 1. PASS：长期展示
     * 2. PENDING：只在短时间窗口内乐观展示
     * 3. REJECT / 超时 PENDING：不展示
     * <p>
     * TODO 如果后续查询条件继续复杂化，迁移到 PostMapper.xml 中写动态 SQL。
     */
    private void applyPublicAuditVisibleCondition(LambdaQueryWrapper<Post> wrapper) {
        LocalDateTime pendingVisibleAfter =
                LocalDateTime.now().minusMinutes(PENDING_AUDIT_VISIBLE_MINUTES);

        wrapper.and(w -> w
                .eq(Post::getAuditStatus, AuditStatus.PASS.getCode())
                .or(ow -> ow
                        .eq(Post::getAuditStatus, AuditStatus.PENDING.getCode())
                        .ge(Post::getPublishedAt, pendingVisibleAfter)
                )
        );
    }

    private List<AuditReserveBO.MediaItem> buildAuditReserveMediaItems(List<AttachmentItemBO> attachmentItems) {
        if (CollectionUtils.isEmpty(attachmentItems)) {
            return List.of();
        }

        return attachmentItems.stream()
                .filter(Objects::nonNull)
                .filter(item -> StringUtils.hasText(item.getUrl()))
                .map(item -> AuditReserveBO.MediaItem.builder()
                        .mediaType(item.getType())
                        .mediaUrl(item.getUrl())
                        .build())
                .toList();
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
        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .select(Post::getId, Post::getUserId, Post::getTitle, Post::getContent,
                        Post::getCommentCount, Post::getPublishedAt)
                .eq(Post::getStatus, resolveListStatus(query.getStatus()))
                .eq(query.getUserId() != null, Post::getUserId, query.getUserId())
                .apply(StringUtils.hasText(query.getKeyword()),
                        "MATCH(content) AGAINST({0} IN BOOLEAN MODE)",
                        query.getKeyword())
                .lt(query.getLastId() != null, Post::getId, query.getLastId())
                .orderByDesc(Post::getId)
                .last("LIMIT " + pageSize);

        // 普通公开列表：PASS 永久展示；PENDING 只在短时间内展示。
        // 管理员查询 REJECTED / DELETED 等其他状态时，不套这个公开展示规则。
        if (query.getStatus() == null
                || query.getStatus().equals(PostStatus.PUBLISHED.getCode())) {
            applyPublicAuditVisibleCondition(wrapper);
        }

        return wrapper;
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private void enqueueAudit(Long postId, String content, AuditReserveResultBO reserveResult) {
        if (reserveResult == null || !reserveResult.hasAuditTask()) {
            return;
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
                        .auditScene(AuditScene.POST)
                        .targetId(postId)
                        .scene(3)
                        .items(items)
                        .build());
            }
        }

        if (reserveResult.getTextAuditLogId() != null && StringUtils.hasText(content)) {
            queueProducer.send(
                    AuditTextPayload.builder()
                            .auditLogId(reserveResult.getTextAuditLogId())
                            .targetId(postId)
                            .auditScene(AuditScene.POST)
                            .content(content)
                            .scene(3)
                            .build()
            );
        }
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

        LambdaQueryWrapper<Post> wrapper = new LambdaQueryWrapper<Post>()
                .select(Post::getId, Post::getUserId, Post::getTitle, Post::getContent,
                        Post::getViewCount, Post::getLikeCount, Post::getCommentCount,
                        Post::getPublishedAt, Post::getAuditStatus)
                .eq(Post::getStatus, PostStatus.PUBLISHED.getCode())
                .eq(Post::getId, postId);

        applyPublicAuditVisibleCondition(wrapper);

        Post post = postMapper.selectOne(wrapper);

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
