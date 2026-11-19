package cn.jualn.miniapp.module.article.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("article_category")
public class ArticleCategory {
 @TableId(type=IdType.AUTO) private Long id;
 private String name;
 private String sceneCodes;
 private Long parentId;
 private Integer displayOrder;
 private Boolean enabled;
 private Long revision;
 private Long createdBy, updatedBy;
 private LocalDateTime createdAt, updatedAt;
}
