package cn.jualn.miniapp.module.post.bo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

public final class PostCommunityBO {
    private PostCommunityBO() { }
    public record CreationReceipt(String fingerprint,Long targetId) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Lifecycle(OffsetDateTime acceptedAt, String submissionVersion, @JsonInclude(JsonInclude.Include.ALWAYS) String effectiveVersion,
            String visibility, String safety, boolean authorDeleted, boolean adminHidden,
            OffsetDateTime temporaryVisibleUntil) { }
    public record Notice(String code,String message,OffsetDateTime occurredAt) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Submission(Long id, Lifecycle lifecycle, List<String> availableActions,Notice notice) { }
    public record Mine(PostDetailBO post, Submission submission) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page<T>(List<T> items, boolean hasMore, String nextCursor) { }
    public record HistoryCleared(OffsetDateTime clearedThrough) { }
    public record FavoriteState(Long postId, boolean favorited) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Saved(Long postId, String availability, PostDetailBO post,
            OffsetDateTime favoritedAt, OffsetDateTime lastViewedAt, OffsetDateTime expiresAt) { }
    public record Management(Long id, Long operatorId, String action, String reason, Long revision,
            @com.fasterxml.jackson.annotation.JsonIgnore java.time.LocalDateTime createdAt) {
        @com.fasterxml.jackson.annotation.JsonProperty("occurredAt") public OffsetDateTime occurredAt() { return createdAt.atOffset(java.time.ZoneOffset.ofHours(8)); }
    }
}
