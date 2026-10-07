package cn.jualn.miniapp.module.activity.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivitySummaryVO {
    private String activityId;
    private String title;
    private String summary;
    private String category;
    private String organizer;
    private AudienceScope audienceScope;
    private String audienceSummary;
    private String primaryLocation;
    private ActivityDetailResourceVO.TimelineNode cardTimeline;
    private String registrationMode;
    private String participantMode;
    private Integer capacity;
    private String capacityUnit;
    private String publishStatus;
    private String lifecycleStatus;
    private Availability availability;
    private PlatformRegistrationCount platformRegistrationCount;
    private Attachment cover;
    public record Attachment(String attachmentId, String kind, String name, String url) {
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AudienceScope(String type, List<String> departmentIds) {
    }
    public record Availability(String state, OffsetDateTime evaluatedAt) {
    }
    public record PlatformRegistrationCount(long submittedCount, OffsetDateTime asOf) {
    }
}
