package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.module.media.bo.AttachmentLinkBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AdminActivitySaveBO {
    private boolean canonicalFullReplacement;
    private com.fasterxml.jackson.databind.JsonNode formSchema;
    private String summary;
    private String audienceSummary;
    private String audienceDepartmentIds;
    private String contactsJson;
    private Integer registrationMode;
    private Integer participantMode;
    private Integer capacityUnit;
    private Integer capacity;
    private Long coverAttachmentId;
    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO> sections;
    private java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventActionBO> actions;


    private Long id;
    private Long operatorId;
    private String title;
    private String location;
    private ActivityCategory category;
    private String organizer;
    private Integer audienceScope;
    private List<TimelineItemBO> timelineItems;
    private List<AttachmentLinkBO> attachmentLinks;
}
