package cn.jualn.miniapp.module.officialcontent.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("official_content")
public class OfficialContent {
    @TableId(type = IdType.AUTO) private Long id;
    private String title;
    private String summary;
    private String body;
    private String publisher;
    private String category;
    private String publishStatus;
    private Long videoAttachmentId;
    private Long createdBy;
    private Long updatedBy;
    private Long revision;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
