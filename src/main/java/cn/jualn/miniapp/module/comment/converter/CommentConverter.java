package cn.jualn.miniapp.module.comment.converter;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;
import cn.jualn.miniapp.module.comment.bo.CommentPageBO;
import cn.jualn.miniapp.module.comment.dto.request.CommentCreateRequest;
import cn.jualn.miniapp.module.comment.dto.request.CommentPageQuery;
import cn.jualn.miniapp.module.comment.entity.Comment;
import cn.jualn.miniapp.module.comment.bo.CommentReadContext;
import cn.jualn.miniapp.module.comment.bo.CommentItemBO;
import cn.jualn.miniapp.module.comment.bo.ReplyItemBO;
import cn.jualn.miniapp.module.comment.vo.CommentVO;
import cn.jualn.miniapp.module.comment.vo.ReplyVO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Map;

/**
 * Comment converter.
 */
@Mapper(componentModel = "spring")
public interface CommentConverter {

    CommentCreateBO toCreateBO(CommentCreateRequest request);

    CommentPageBO toPageBO(CommentPageQuery query);


    @Mapping(target = "author", expression = "java(mapUserSimple(comment.getUserId(),context.getUserMap()))")
    @Mapping(target = "liked", expression = "java(mapLiked(comment.getId(), context.getLikedMap()))")
    @Mapping(target = "likeCount", expression = "java(mapLikedCount(comment.getId(), context.getLikeCountMap()))")
    @Mapping(target = "previewReplies", expression = "java(mapPreviewReplies(comment.getId(), context.getPreviewRepliesMap()))")
    @Mapping(target = "replyPermission", ignore = true)
    CommentItemBO toCommentItemBO(Comment comment, @Context CommentReadContext context);

    @Mapping(target = "author", expression = "java(mapUserSimple(comment.getUserId(),context.getUserMap()))")
    @Mapping(target = "replyToUser", expression = "java(mapReplyUserSimple(comment.getReplyToUid(),context.getReplyUserMap()))")
    @Mapping(target = "liked", expression = "java(mapLiked(comment.getId(), context.getLikedMap()))")
    @Mapping(target = "likeCount", expression = "java(mapLikedCount(comment.getId(), context.getLikeCountMap()))")
    @Mapping(target = "replyPermission", ignore = true)
    ReplyItemBO toReplyItemBO(Comment comment, @Context CommentReadContext context);

    List<CommentItemBO> toItemBOList(List<Comment> comments, @Context CommentReadContext context);

    List<ReplyItemBO> toReplyItemBOList(List<Comment> comments, @Context CommentReadContext context);

    CommentVO toCommentVO(CommentItemBO item);
    ReplyVO toReplyVO(ReplyItemBO item);
    List<CommentVO> toVOList(List<CommentItemBO> items);
    List<ReplyVO> toReplyVOList(List<ReplyItemBO> items);

    default UserSimpleBO mapUserSimple(Long userId, @Context Map<Long, UserSimpleBO> userMap) {
        return userMap.getOrDefault(userId, null);
    }

    default UserSimpleBO mapReplyUserSimple(Long replyUserId, @Context Map<Long, UserSimpleBO> replyUserMap) {
        return replyUserMap.getOrDefault(replyUserId, null);
    }

    default Boolean mapLiked(Long id, @Context Map<Long, Boolean> likedMap) {
        return likedMap.getOrDefault(id, false);
    }

    default Integer mapLikedCount(Long id, @Context Map<Long, Integer> likeCountMap) {
        return likeCountMap.getOrDefault(id, 0);
    }

    default List<ReplyItemBO> mapPreviewReplies(Long id, @Context Map<Long, List<ReplyItemBO>> previewRepliesMap) {
        return previewRepliesMap.getOrDefault(id, List.of());
    }

    default Integer resolveTypeCode(TargetType targetType) {
        return targetType.getCode();
    }

    default TargetType resolveTargetType(Integer targetType) {
        return TargetType.fromCode(targetType);
    }
}
