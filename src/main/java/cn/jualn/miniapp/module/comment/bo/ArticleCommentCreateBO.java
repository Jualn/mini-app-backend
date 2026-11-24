package cn.jualn.miniapp.module.comment.bo;
public record ArticleCommentCreateBO(String content,Long parentId,Long replyToCommentId) {}
