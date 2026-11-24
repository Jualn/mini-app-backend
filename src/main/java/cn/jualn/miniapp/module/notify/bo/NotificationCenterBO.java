package cn.jualn.miniapp.module.notify.bo;

import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import java.util.List;

/** Service results and frozen display values; never populated from current subject details on reads. */
public final class NotificationCenterBO {
    private NotificationCenterBO() { }
    public record Query(String cursor, int pageSize, NotificationCategory category, String boxCategory, Boolean isRead) { }
    public record Actor(String userId, String nickname, String avatarUrl) { }
    public record Subject(String type, String resourceId) { }
    public record Change(String label, String before, String after) { }
    public record Presentation(String title, String body, String context, String thumbnailUrl, List<Change> changes,
                               String subjectTitle, String quote) {
        public Presentation(String title, String body, String context, String thumbnailUrl, List<Change> changes) {
            this(title, body, context, thumbnailUrl, changes, null, null);
        }
    }
    public record Target(String type, String postId, String commentId, String activityId, String publicEventId,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String articleId,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String taskId,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String assignmentId,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String reportId,
                         @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Boolean growthRecordTarget) {
        public Target(String type,String postId,String commentId,String activityId,String publicEventId,String articleId,String taskId,String assignmentId,String reportId){this(type,postId,commentId,activityId,publicEventId,articleId,taskId,assignmentId,reportId,null);}
        public Target(String type,String postId,String commentId,String activityId,String publicEventId,String articleId,String taskId,String assignmentId) {this(type,postId,commentId,activityId,publicEventId,articleId,taskId,assignmentId,null);}
        public Target(String type,String postId,String commentId,String activityId,String publicEventId,String articleId,String taskId) {this(type,postId,commentId,activityId,publicEventId,articleId,taskId,null);}
        public Target(String type,String postId,String commentId,String activityId,String publicEventId,String articleId) {
            this(type,postId,commentId,activityId,publicEventId,articleId,null);
        }
        public Target(String type,String postId,String commentId,String activityId,String publicEventId) {
            this(type,postId,commentId,activityId,publicEventId,null,null);
        }
    }
    public record Item(String id, String category, String type, Presentation presentation, Actor actor,
                       Subject subject, Target target, String readAt, String createdAt) { }
    public record Preview(String id, String type, String title, String body, Target target, String createdAt) { }
    public record Page(List<Item> items, String nextCursor, boolean hasMore, String headCursor) { }
    public record Summary(int unreadCount, String headCursor, int newCount, Preview latestNewNotification) { }
    public record ReadResult(int changedCount, int unreadCount) { }
    public record Snapshot(Presentation presentation, Actor actor, Subject subject, Target target) { }
}
