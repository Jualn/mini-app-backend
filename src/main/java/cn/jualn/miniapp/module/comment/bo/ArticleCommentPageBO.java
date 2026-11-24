package cn.jualn.miniapp.module.comment.bo;
import java.util.List;
public record ArticleCommentPageBO(List<ArticleCommentBO> items,String nextCursor) {}
