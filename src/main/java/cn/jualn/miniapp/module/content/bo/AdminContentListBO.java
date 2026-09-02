package cn.jualn.miniapp.module.content.bo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminContentListBO {
    private Long id;
    private String type;
    private String title;
    private String contentPreview;
    private String authorName;
    private Long authorId;
    private String status;
    private String auditStatus;
    private Boolean pinned;
    private Boolean featured;
    private Long reportCount;
    private Long viewCount;
    private Long likeCount;
    private Long commentCount;
    private Long shareCount;
    private LocalDateTime publishedAt;
    private LocalDateTime updatedAt;
    private Integer rawStatus;
    private List<String> availableActions;
}
