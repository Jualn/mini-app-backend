package cn.jualn.miniapp.module.audit.mapper;

import java.time.LocalDateTime;
import lombok.Data;

/** Review-owned projections. Locking reads preserve parent/root/comment arbitration order. */
public final class ReviewSubjectRows {
    private ReviewSubjectRows() { }
    @Data public static class Post {
        private Long id;
        private String questionVersion;
        private String title;
        private String content;
        private String postType;
        private Long revision;
        private Long auditRevision;
        private Boolean adminHidden;
        private Boolean communityVersion;
        private Integer auditStatus;
        private LocalDateTime temporaryVisibleUntil;
        private Integer status;
        private LocalDateTime deletedAt;
    }
    @Data public static class Comment {
        private Long id;
        private String questionVersion;
        private Integer targetType;
        private Long targetId;
        private Long parentId;
        private Long replyToCommentId;
        private Boolean communityThread;
        private String content;
        private String imageUrl;
        private Integer status;
        private LocalDateTime deletedAt;
    }
}
