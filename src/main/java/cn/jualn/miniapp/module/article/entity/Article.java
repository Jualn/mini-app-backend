package cn.jualn.miniapp.module.article.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("article")
public class Article {
 @TableId(type=IdType.AUTO) private Long id;
 private String title, summary, body, plainText, publishStatus;
 private Long categoryId, publisherId, coverAttachmentId, revision, createdBy, updatedBy;
 private String publisherOrgId, publisherNameSnapshot;
 @TableField(exist=false) private Boolean organizationRepresentation;
 private Boolean commentsEnabled;
 private String attachmentIds, source;
 private LocalDateTime deletedAt;
 private Long deletedBy;
 private LocalDateTime createdAt, updatedAt, publishedAt, lastPublishedAt;
 private Long viewCount, likeCount, commentCount, shareCount;
}
