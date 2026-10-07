package cn.jualn.miniapp.module.exam.vo.admin;

import cn.jualn.miniapp.module.exam.vo.PublicEventDetailVO;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminPublicEventDraftResourceVO {
    private String title;
    private String summary;
    private String coverAttachmentId;
    private String type;
    private String sourceName;
    private String sourceUrl;
    private String officialUrl;
    private List<PublicEventDetailVO.TimelineNode> timeline;
    private List<PublicEventDetailVO.Section> sections;
    private List<PublicEventDetailVO.Action> actions;
    private List<PublicEventDetailVO.Contact> contacts;
    private List<AttachmentLink> attachments;

    public record AttachmentLink(String attachmentId, int displayOrder) {}
}
