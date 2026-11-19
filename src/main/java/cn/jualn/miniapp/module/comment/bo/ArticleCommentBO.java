package cn.jualn.miniapp.module.comment.bo;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import java.time.LocalDateTime;
public record ArticleCommentBO(Long id,Long articleId,String content,UserSimpleBO author,LocalDateTime createdAt,
 Long parentId,Long replyToCommentId,long likeCount,boolean liked,long replyCount) {}
