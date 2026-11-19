package cn.jualn.miniapp.module.article.vo;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import cn.jualn.miniapp.module.media.vo.AttachmentVO;
import java.util.List;
public final class ArticleRepresentations {
 private ArticleRepresentations() {}
 public record Operator(String identityType,String operatorId) {}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record Category(String id,String parentId,String name,int displayOrder,boolean enabled,String createdAt,String updatedAt,List<String> sceneCodes) {}
 public record Publisher(String id,String displayName,String updatedAt,Operator updatedBy) {}
 public record PublicPublisher(String id,String displayName) {}
 public record Statistics(long viewCount,long likeCount,long commentCount,long shareCount) {}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record AdminDetail(String id,String title,String summary,@JsonInclude(JsonInclude.Include.ALWAYS) String categoryId,
  String publisherId,String coverAttachmentId,JsonNode body,boolean commentsEnabled,String plainText,String publishStatus,
  String publishedAt,String lastPublishedAt,String createdAt,String updatedAt,Operator createdBy,Operator updatedBy,String publisherOrgId,String publisherNameSnapshot,List<String> attachmentIds,JsonNode source,String deletedAt,Operator deletedBy) {}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record AdminSummary(String id,String title,String summary,@JsonInclude(JsonInclude.Include.ALWAYS) String categoryId,
  String publisherId,String coverAttachmentId,boolean commentsEnabled,String publishStatus,
  String publishedAt,String lastPublishedAt,String createdAt,String updatedAt,String publisherOrgId,String publisherNameSnapshot,String deletedAt,Operator deletedBy) {}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record Summary(String id,String title,String summary,AttachmentVO cover,Category category,PublicPublisher publisher,
  String publishedAt,boolean commentsEnabled,Statistics statistics,String publisherOrgId,String publisherNameSnapshot) {}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record Detail(String id,String title,String summary,AttachmentVO cover,Category category,PublicPublisher publisher,
  String publishedAt,boolean commentsEnabled,Statistics statistics,JsonNode body,String plainText,List<AttachmentVO> media,String publisherOrgId,String publisherNameSnapshot,List<AttachmentVO> attachments,JsonNode source) {}
 @JsonInclude(JsonInclude.Include.NON_NULL)
 public record Page(List<Summary> items,String nextCursor) {}
 public record AdminPage(List<AdminSummary> items,int page,int pageSize,long totalItems) {}
 public record Categories(List<Category> items) {}
 public record Publishers(List<Publisher> items) {}
}
