package cn.jualn.miniapp.module.exam.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import cn.jualn.miniapp.module.timeline.dto.request.TimelineScheduleRequest;
import cn.jualn.miniapp.module.timeline.model.TimelineSemantic;
import lombok.Data;

/** Canonical PublicEventDraft used by both POST and full-replacement PUT. */
@Data
public class AdminPublicEventSaveRequest {
    @NotBlank @Size(max = 200) private String title;
    @Size(min = 1, max = 1000) private String summary;
    @Size(max = 128) private String coverAttachmentId;
    @Pattern(regexp = "EXAM|COMPETITION|CERTIFICATION|OTHER") private String type;
    @Size(min = 1, max = 200) private String sourceName;
    @Pattern(regexp = "^https?://.+") private String sourceUrl;
    @Pattern(regexp = "^https?://.+") private String officialUrl;
    @NotNull @Valid @Size(max = 100) private List<TimelineNode> timeline;
    @NotNull @Valid @Size(max = 50) private List<Section> sections;
    @NotNull @Valid @Size(max = 50) private List<Action> actions;
    @NotNull @Valid @Size(max = 20) private List<Contact> contacts;
    @NotNull @Valid @Size(max = 100) private List<AttachmentLink> attachments;

    @Data public static class TimelineNode {
        @NotBlank @Size(max = 128) private String nodeKey;
        @NotBlank @Pattern(regexp = TimelineSemantic.PUBLIC_EVENT_INPUT_PATTERN) private String type;
        @NotBlank @Size(max = 120) private String title;
        @Size(min = 1, max = 2000) private String description;
        @NotNull @Valid private TimelineScheduleRequest schedule;
        @Size(min = 1, max = 300) private String location;
        @NotNull @Min(0) private Integer displayOrder;
    }

    @Data public static class Section {
        @NotBlank @Size(max = 128) private String sectionKey;
        @NotBlank @Size(max = 120) private String title;
        @NotBlank @Size(max = 50000) private String content;
        @NotBlank @Pattern(regexp = "PLAIN_TEXT|MARKDOWN") private String format;
        @NotNull @Min(0) private Integer displayOrder;
    }

    @Data public static class Action {
        @NotBlank @Size(max = 128) private String actionKey;
        @NotBlank @Pattern(regexp = "JOIN_GROUP|OFFICIAL_SITE|EXTERNAL_REGISTRATION|DOWNLOAD|VIEW_ATTACHMENT|EMAIL_SUBMISSION|OFFICIAL_NOTICE|OTHER") private String type;
        @NotBlank @Size(max = 120) private String title;
        @Size(min = 1, max = 2000) private String description;
        @Pattern(regexp = "^(https?://|mailto:).+") private String url;
        @Size(max = 128) private String attachmentId;
        @NotNull @Min(0) private Integer displayOrder;
    }

    @Data public static class Contact {
        @NotBlank @Size(max = 128) private String contactKey;
        @NotBlank @Size(max = 80) private String name;
        @NotBlank @Size(max = 300) private String contact;
        @Size(min = 1, max = 500) private String remark;
    }

    @Data public static class AttachmentLink {
        @NotBlank @Size(max = 128) private String attachmentId;
        @NotNull @Min(0) private Integer displayOrder;
    }
}
