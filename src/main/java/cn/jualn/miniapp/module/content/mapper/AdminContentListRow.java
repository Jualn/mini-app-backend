package cn.jualn.miniapp.module.content.mapper;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminContentListRow {
    private Long id;
    private String type;
    private String title;
    private String contentPreview;
    private Long authorId;
    private String authorName;
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
    private LocalDateTime deletedAt;
    private Integer rawStatus;
}
