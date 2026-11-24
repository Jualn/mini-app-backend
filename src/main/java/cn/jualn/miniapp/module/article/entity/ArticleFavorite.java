package cn.jualn.miniapp.module.article.entity;
import java.time.LocalDateTime;
public record ArticleFavorite(long articleId,LocalDateTime favoritedAt) {}
