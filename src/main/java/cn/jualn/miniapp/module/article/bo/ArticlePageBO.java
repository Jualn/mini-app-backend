package cn.jualn.miniapp.module.article.bo;
import java.util.List;
public record ArticlePageBO(List<ArticleBO> items, String nextCursor) {}
