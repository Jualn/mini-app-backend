package cn.jualn.miniapp.module.article.bo;
import com.fasterxml.jackson.databind.JsonNode;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import java.time.LocalDateTime;
import java.util.List;
public record ArticleBO(Long id, String title, String summary, Long categoryId, Long publisherId,
 Long coverAttachmentId, JsonNode body, String plainText, boolean commentsEnabled, String publishStatus,
 Long revision, Long createdBy, Long updatedBy, LocalDateTime createdAt, LocalDateTime updatedAt,
 LocalDateTime publishedAt, LocalDateTime lastPublishedAt, ArticleCategoryBO category, ArticlePublisherBO publisher,
 ArticleStatisticsBO statistics, List<MediaAttachmentBO> media, String publisherOrgId, String publisherNameSnapshot, List<Long> attachmentIds, JsonNode source, LocalDateTime deletedAt,Long deletedBy) {
 public ArticleBO(Long id, String title, String summary, Long categoryId, Long publisherId,
 Long coverAttachmentId, JsonNode body, String plainText, boolean commentsEnabled, String publishStatus,
 Long revision, Long createdBy, Long updatedBy, LocalDateTime createdAt, LocalDateTime updatedAt,
 LocalDateTime publishedAt, LocalDateTime lastPublishedAt, ArticleCategoryBO category, ArticlePublisherBO publisher,
 ArticleStatisticsBO statistics, List<MediaAttachmentBO> media, String publisherOrgId, String publisherNameSnapshot) {this(id,title,summary,categoryId,publisherId,coverAttachmentId,body,plainText,commentsEnabled,publishStatus,revision,createdBy,updatedBy,createdAt,updatedAt,publishedAt,lastPublishedAt,category,publisher,statistics,media,publisherOrgId,publisherNameSnapshot,List.of(),null,null,null);}
}
