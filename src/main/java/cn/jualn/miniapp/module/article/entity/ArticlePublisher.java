package cn.jualn.miniapp.module.article.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("article_publisher")
public class ArticlePublisher {
 @TableId(type=IdType.INPUT) private Long id;
 private String displayName;
 private Long revision, updatedBy;
 private LocalDateTime updatedAt;
}
