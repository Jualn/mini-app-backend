package cn.jualn.miniapp.module.exam.bo;

import cn.jualn.miniapp.module.eventcontent.bo.EventActionBO;
import cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO;
import cn.jualn.miniapp.module.media.bo.AttachmentLinkBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminPublicEventSaveBO {
    private Long id;
    private Long operatorId;
    private boolean canonicalFullReplacement;
    private String title;
    private String summary;
    private Integer eventType;
    private String sourceName;
    private String sourceUrl;
    private String officialUrl;
    private Long coverAttachmentId;
    private String contactsJson;
    private List<EventSectionBO> sections;
    private List<EventActionBO> actions;
    private List<AttachmentLinkBO> attachmentLinks;
    private List<TimelineItemBO> timeline;
}
