package cn.jualn.miniapp.module.content.vo.admin;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminContentListVO {
    private String id;
    private String type;
    private String title;
    private String contentPreview;
    private String authorName;
    private String authorId;
    private String status;
    private String auditStatus;
    private Boolean isPinned;
    private Boolean isFeatured;
    private Long reportCount;
    private Metrics metrics;
    private LocalDateTime publishedAt;
    private LocalDateTime updatedAt;
    private List<String> availableActions;

    @Data
    public static class Metrics {
        private Long views;
        private Long likes;
        private Long comments;
        private Long shares;
    }
}
