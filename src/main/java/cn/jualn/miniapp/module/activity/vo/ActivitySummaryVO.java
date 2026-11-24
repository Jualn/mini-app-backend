package cn.jualn.miniapp.module.activity.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivitySummaryVO {
    private Boolean allowRegistrationAfterStart;
    private Availability stage;
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attachment(String attachmentId, String kind, String name, String url,
            String retention, java.time.OffsetDateTime expiresAt) {
        public Attachment(String id,String kind,String name,String url) {this(id,kind,name,url,null,null);}
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AudienceScope(String type, List<String> departmentIds) {
    }
    public record Availability(String state, OffsetDateTime evaluatedAt) {
    }
    public record PlatformRegistrationCount(long submittedCount, OffsetDateTime asOf) {
    }
}
