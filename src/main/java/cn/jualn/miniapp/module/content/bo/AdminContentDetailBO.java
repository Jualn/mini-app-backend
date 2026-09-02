package cn.jualn.miniapp.module.content.bo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdminContentDetailBO extends AdminContentListBO {
    private String content;
    private Author author;
    private List<Attachment> attachments;
    private List<ContextItem> context;
    private List<HistoryItem> history;
    private String rejectReason;
    private String imageUrl;
    private Integer targetType;
    private Long targetId;
    private Long parentId;

    @Data
    public static class Author {
        private String avatarText;
        private String statusLabel;
        private LocalDateTime joinedAt;
        private Long publishedCount;
        private Long violationCount;
    }

    @Data
    public static class Attachment {
        private String id;
        private String type;
        private String name;
        private String url;
    }

    @Data
    public static class ContextItem {
        private String label;
        private String value;
    }

    @Data
    public static class HistoryItem {
        private String title;
        private String detail;
        private String operator;
        private LocalDateTime createdAt;
        private String tone;
    }
}
