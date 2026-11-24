package cn.jualn.miniapp.module.article.bo;
import java.time.LocalDateTime;
public record ArticlePublisherBO(Long id, String displayName, Long revision, LocalDateTime updatedAt, Long updatedBy) {}
