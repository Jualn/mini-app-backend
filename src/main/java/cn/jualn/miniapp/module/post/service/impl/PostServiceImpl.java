package cn.jualn.miniapp.module.post.service.impl;

import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.audit.bo.AuditReserveBO;
import cn.jualn.miniapp.module.audit.bo.AuditReserveResultBO;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.service.AuditReservationService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.post.bo.PostCreateBO;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.enums.PostStatus;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.service.PostService;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {
    private final cn.jualn.miniapp.module.authorization.service.CurrentAuthorization authorization;
    private final cn.jualn.miniapp.module.post.service.PostOrganizationService organization;
    private final cn.jualn.miniapp.module.post.service.PostCommentPermissions permissions;
    private final PostMapper postMapper;
    private final MediaService mediaService;
    private final cn.jualn.miniapp.module.media.service.MediaQueries mediaQueries;
    private final UserService userService;
    private final UserAccountService userAccounts;
    private final RedisService redisService;
    private final AuditReservationService auditReservationService;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<cn.jualn.miniapp.module.audit.service.ContentSafetyInvalidation> safetyReviews;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.post.service.GrowthRecordAccess growth;

    @Transactional(rollbackFor = Exception.class)
    public PostListBO createPost(PostCreateBO command) {
        if (command == null) {
            throw new BusinessException(ResultCode.POST_PARAM_INVALID, "请求参数不能为空");
        }

        Long userId = requireUserId();
        userAccounts.lockCommunityWriter(userId);
        long start = System.currentTimeMillis();
        log.debug("[PostService.createPost][开始] userId={}", userId);

        if(command.getContent()==null || (command.getContent().isBlank() && (command.getPostType()!=cn.jualn.miniapp.module.post.PostType.NORMAL || command.getAttachmentItems()==null || command.getAttachmentItems().stream().noneMatch(m->m!=null && m.getType()==cn.jualn.miniapp.module.media.enums.MediaType.IMAGE))) || command.getContent().length()>5000 || (command.getTitle()!=null && command.getTitle().length()>128))
            { throw cn.jualn.miniapp.module.post.PostProblems.invalid("content","INVALID_VALUE","Invalid post content"); }
        if (command.getPostType() == null) { throw cn.jualn.miniapp.module.post.PostProblems.invalid("postType", "INVALID_VALUE", "Unsupported post type"); }
        if(command.getAttachmentItems()!=null && (command.getAttachmentItems().size()>9 || command.getAttachmentItems().stream().anyMatch(m->m==null || m.getType()==null || !Set.of(cn.jualn.miniapp.module.media.enums.MediaType.URL,cn.jualn.miniapp.module.media.enums.MediaType.IMAGE,cn.jualn.miniapp.module.media.enums.MediaType.PDF,cn.jualn.miniapp.module.media.enums.MediaType.WORD).contains(m.getType()))))
            { throw cn.jualn.miniapp.module.post.PostProblems.invalid("attachmentItems","INVALID_VALUE","Unsupported Post attachments"); }
        var topicIds = command.getTopicIds() == null ? List.<Long>of() : command.getTopicIds();
        organization.validateReferences(command.getPostType().name(), command.getCategoryId(), topicIds);
        var acceptedAt=LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        Post post = Post.builder()
                .postType(command.getPostType().name()).categoryId(command.getCategoryId()).revision(0L).auditRevision(0L)
                .questionVersion(command.getPostType()==cn.jualn.miniapp.module.post.PostType.MENTOR_QA?java.util.UUID.randomUUID().toString():null).questionContentLocked(false)
                .communityVersion(true).adminHidden(false)
                .acceptedAt(acceptedAt)
                .temporaryVisibleUntil(acceptedAt.plusMinutes(10))
                .userId(userId)
                .title(command.getTitle())
                .content(command.getContent())
                // 乐观发布：创建后先可见
                .status(PostStatus.PUBLISHED.getCode())
                // 默认先置为 PENDING，后面根据是否真的有审核任务修正
                .auditStatus(AuditStatus.PENDING.getCode())
                .publishedAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))
                .build();

        postMapper.insert(post);
        organization.replaceTopics(post.getId(), topicIds);

        if (!CollectionUtils.isEmpty(command.getAttachmentItems())) {
            var mediaCommand=MediaAttachmentSaveBO.builder().targetType(TargetType.POST)
                    .targetId(post.getId()).attachments(command.getAttachmentItems()).build();
            mediaService.replaceAttachments(mediaCommand);
            command.setAttachmentItems(mediaCommand.getAttachments());
        }

        AuditReserveResultBO reserveResult = auditReservationService.reserveAuditLogs(
                AuditReserveBO.builder()
                        .auditScene(AuditScene.POST)
                        .targetId(post.getId())
                        .textContent((post.getTitle()==null?"":post.getTitle()+"\n")+post.getContent())
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
                mediaQueries.listSimpleAttachments(TargetType.POST,post.getId());


        log.debug("[PostService.createPost][完成] userId={}, postId={}, costMs={}",
                userId, post.getId(), System.currentTimeMillis() - start);

        var created = PostListBO.builder()
                .id(post.getId())
                .title(post.getTitle())
                .content(post.getContent())
                .likeCount(0)
                .commentCount(0)
                .viewCount(0)
                .publishedAt(post.getPublishedAt())
                .author(author)
                .attachments(attachments)
                .liked(false).postType(post.getPostType())
                .build();
        enrichOrganization(List.of(post), List.of(created));
        return created;
    }

    @Transactional(rollbackFor = Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void removePost(Long postId) {
        Long operatorId = requireUserId();
        if(growth.link(postId)!=null)growth.guard(operatorId);
        Long postAuthorId = postMapper.selectUserIdById(postId);
        if (postAuthorId == null) {
            throw new BusinessException(ResultCode.POST_NOT_FOUND, "帖子不存在");
        }
        if (!Objects.equals(operatorId,postAuthorId)) throw new BusinessException(ResultCode.POST_NOT_FOUND);
        var locked=postMapper.lockPost(postId);
        if(growth.link(postId)!=null)growth.original(locked,operatorId,false);

        postMapper.update(
                new LambdaUpdateWrapper<Post>()
                        .set(Post::getStatus, PostStatus.DELETED.getCode())
                        .set(Post::getDeletedAt, LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))
                        .eq(Post::getId, postId)
        );
        safetyReviews.getObject().invalidateTarget(1,postId,"target-deleted");
        redisService.delete(buildPostDetailCacheKey(postId));
//        afterCommit(() -> searchService.removeByTarget(TargetType.POST, postId));
        log.info("[PostService.removePost][完成] operatorId={}, postId={}", operatorId, postId);
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

    private void enrichOrganization(List<Post> posts,List<PostListBO> rows) {
        var projection=organization.project(posts.stream().map(Post::getId).toList(),posts.stream().map(Post::getCategoryId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<String,cn.jualn.miniapp.module.post.bo.PostWritePermission> permissionMap=new HashMap<>();
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i); var post=posts.get(i);
            row.setPostType(post.getPostType()); row.setCategory(post.getCategoryId()==null?null:projection.categories().get(post.getCategoryId()));
            row.setTopics(projection.topics().getOrDefault(post.getId(),List.of()));
            row.setCommentPermission(permissionMap.computeIfAbsent(post.getPostType(),type->"MENTOR_QA".equals(type)?cn.jualn.miniapp.module.post.bo.PostWritePermission.blocked():permissions.answer(UserContext.getUserId(),type)));
        }
    }

    private Long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }

    private void assertCanManagePost(Long operatorId, Long postAuthorId) {
        if (Objects.equals(operatorId, postAuthorId)) {
            return;
        }

        authorization.requireLegacyEntry(operatorId,"content:manage","POST","post:dispose");
    }

    private String buildPostDetailCacheKey(Long postId) {
        return RedisKeyConstant.postDetail(postId);
    }
}
