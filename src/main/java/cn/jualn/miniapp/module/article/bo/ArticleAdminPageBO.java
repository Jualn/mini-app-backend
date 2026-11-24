package cn.jualn.miniapp.module.article.bo;
import java.util.List;
public record ArticleAdminPageBO(List<ArticleBO> items, int page, int pageSize, long totalItems) {}
