package cn.jualn.miniapp.module.post.converter;

import cn.jualn.miniapp.common.mapper.EnumConverter;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.post.bo.PostCreateBO;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.bo.PostSearchBO;
import cn.jualn.miniapp.module.post.dto.request.PostCreateRequest;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.bo.PostDetailBO;
import cn.jualn.miniapp.module.post.service.impl.PostServiceImpl;
import cn.jualn.miniapp.module.post.vo.PostDetailVO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Map;

/**
 * 帖子对象转换器。
 */
@Mapper(componentModel = "spring", uses = {EnumConverter.class})
public interface PostConverter {

    /**
     * 创建请求转帖子实体。
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "auditStatus", ignore = true)
    @Mapping(target = "rejectReason", ignore = true)
    @Mapping(target = "isPinned", ignore = true)
    @Mapping(target = "isFeatured", ignore = true)
    @Mapping(target = "commentCount", ignore = true)
    @Mapping(target = "likeCount", ignore = true)
    @Mapping(target = "shareCount", ignore = true)
    @Mapping(target = "viewCount", ignore = true)
    @Mapping(target = "publishedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    Post toEntity(PostCreateRequest request);

    PostCreateBO toCreateBO(PostCreateRequest request);

    /**
     * 帖子实体转详情响应。
     */
    @Mapping(target = "author", ignore = true)
    @Mapping(target = "attachments", ignore = true)
    PostDetailBO toDetailBO(Post post);

    @Mapping(target = "liked", ignore = true)
    PostDetailVO toDetailVO(PostDetailBO postDetailBO);

    @Mapping(target = "author", expression = "java(mapUserSimple(post.getUserId(), context.getUserMap()))")
    @Mapping(target = "liked", expression = "java(mapLiked(post.getId(), context.getLikedMap()))")
    @Mapping(target = "likeCount", expression = "java(mapLikeCount(post.getId(), context.getLikeCountMap()))")
    @Mapping(target = "viewCount", expression = "java(mapViewCount(post.getId(), context.getViewCountMap()))")
    @Mapping(target = "attachments", expression = "java(mapAttachments(post.getId(), context.getAttachmentMap()))")
    PostListBO toListBO(Post post, @Context PostServiceImpl.PostContext context);
    List<PostListBO> toListBOList(List<Post> posts, @Context PostServiceImpl.PostContext context);

    PostSearchBO toSearchBO(Post post);

    default UserSimpleBO mapUserSimple(Long userId, @Context Map<Long, UserSimpleBO> userMap) {
        return userMap.getOrDefault(userId, null);
    }
    default Boolean mapLiked(Long postId, @Context Map<Long, Boolean> likedMap) {
        return likedMap.getOrDefault(postId, false);
    }
    default Integer mapLikeCount(Long postId, @Context Map<Long, Integer> likeCountMap) {
        return likeCountMap.getOrDefault(postId, 0);
    }
    default Integer mapViewCount(Long postId, @Context Map<Long, Integer> viewCountMap) {
        return viewCountMap.getOrDefault(postId, 0);
    }
    default List<MediaAttachmentSimpleBO> mapAttachments(Long postId, @Context Map<Long, List<MediaAttachmentSimpleBO>> attachmentMap) {
        return attachmentMap.getOrDefault(postId, List.of());
    }
}