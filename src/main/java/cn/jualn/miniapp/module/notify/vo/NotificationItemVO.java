package cn.jualn.miniapp.module.notify.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationItemVO(String id, String category, String type, Presentation presentation,
        Actor actor, Subject subject, Target target,
        @JsonInclude(JsonInclude.Include.ALWAYS) String readAt, String createdAt) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Actor(String userId, String nickname, String avatarUrl) { }
    public record Subject(String type, String resourceId) { }
    public record Change(String label, String before, String after) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Presentation(String title, String body, String context, String thumbnailUrl, List<Change> changes,
                               String subjectTitle, String quote) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Target(String type, String postId, String commentId, String activityId, String publicEventId) { }
}
