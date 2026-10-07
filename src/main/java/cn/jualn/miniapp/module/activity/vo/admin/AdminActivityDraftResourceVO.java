package cn.jualn.miniapp.module.activity.vo.admin;

import cn.jualn.miniapp.module.activity.vo.ActivityDetailResourceVO;
import cn.jualn.miniapp.module.activity.vo.ActivitySummaryVO;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminActivityDraftResourceVO {
    private String title;
    private String summary;
    private String coverAttachmentId;
    private String category;
    private String organizer;
    private ActivitySummaryVO.AudienceScope audienceScope;
    private String audienceSummary;
    private String primaryLocation;
    private String registrationMode;
    private String participantMode;
    private Integer capacity;
    private String capacityUnit;
    private ActivityDetailResourceVO.RegistrationForm registrationForm;
    private List<ActivityDetailResourceVO.TimelineNode> timeline;
    private List<ActivityDetailResourceVO.Section> sections;
    private List<ActivityDetailResourceVO.Action> actions;
    private List<ActivityDetailResourceVO.Contact> contacts;
    private List<AttachmentLink> attachments;

    public record AttachmentLink(String attachmentId, int displayOrder) {
    }
}
