package cn.jualn.miniapp.module.activity.dto.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import cn.jualn.miniapp.module.timeline.dto.request.TimelineScheduleRequest;
import cn.jualn.miniapp.module.timeline.model.TimelineSemantic;

import lombok.Data;

/**
 * Canonical ActivityDraft. PUT uses the same full-replacement representation as POST.
 */
@Data
public class AdminActivitySaveRequest {
    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(min = 1, max = 1000)
    private String summary;

    @Size(max = 128)
    private String coverAttachmentId;

    @Pattern(regexp = "LECTURE|COMPETITION|SPORTS|VOLUNTEERING|THEMED|OTHER")
    private String category;

    @Size(min = 1, max = 200)
    private String organizer;

    @Valid
    private AudienceScope audienceScope;

    @Size(min = 1, max = 200)
    private String audienceSummary;

    @Size(min = 1, max = 300)
    private String primaryLocation;


    @Pattern(regexp = "NONE|MINI_PROGRAM|EXTERNAL|MINI_PROGRAM_AND_EXTERNAL")
    private String registrationMode;

    @Pattern(regexp = "INDIVIDUAL|TEAM")
    private String participantMode;

    @Positive
    private Integer capacity;

    @Pattern(regexp = "PERSON|TEAM")
    private String capacityUnit;

    @Valid
    private RegistrationForm registrationForm;

    @NotNull
    @Valid
    @Size(max = 100)
    private List<TimelineNode> timeline;

    @NotNull
    @Valid
    @Size(max = 50)
    private List<Section> sections;

    @NotNull
    @Valid
    @Size(max = 50)
    private List<Action> actions;

    @NotNull
    @Valid
    @Size(max = 20)
    private List<Contact> contacts;

    @NotNull
    @Valid
    @Size(max = 100)
    private List<AttachmentLink> attachments;

    @Data
    public static class AudienceScope {
        @NotBlank
        @Pattern(regexp = "CAMPUS|DEPARTMENTS")
        private String type;

        @Size(min = 1)
        private List<@NotBlank @Size(max = 128) String> departmentIds;
    }

    @Data
    public static class TimelineNode {
        @NotBlank
        @Size(max = 128)
        private String nodeKey;

        @NotBlank
        @Pattern(regexp = TimelineSemantic.ACTIVITY_INPUT_PATTERN)
        private String type;

        @NotBlank
        @Size(max = 120)
        private String title;

        @Size(min = 1, max = 2000)
        private String description;

        @NotNull
        @Valid
        private TimelineScheduleRequest schedule;

        @Size(min = 1, max = 300)
        private String location;

        @NotNull
        @Min(0)
        private Integer displayOrder;
    }

    @Data
    public static class Section {
        @NotBlank
        @Size(max = 128)
        private String sectionKey;

        @NotBlank
        @Size(max = 120)
        private String title;

        @NotBlank
        @Size(max = 50000)
        private String content;

        @NotBlank
        @Pattern(regexp = "PLAIN_TEXT|MARKDOWN")
        private String format;

        @NotNull
        @Min(0)
        private Integer displayOrder;
    }

    @Data
    public static class Action {
        @NotBlank
        @Size(max = 128)
        private String actionKey;

        @NotBlank
        @Pattern(regexp = "JOIN_GROUP|OFFICIAL_SITE|EXTERNAL_REGISTRATION|DOWNLOAD|VIEW_ATTACHMENT|EMAIL_SUBMISSION|OFFICIAL_NOTICE|OTHER")
        private String type;

        @NotBlank
        @Size(max = 120)
        private String title;

        @Size(min = 1, max = 2000)
        private String description;

        @Pattern(regexp = "^(https?://|mailto:).+")
        private String url;

        @Size(max = 128)
        private String attachmentId;

        @NotNull
        @Min(0)
        private Integer displayOrder;
    }

    @Data
    public static class Contact {
        @NotBlank
        @Size(max = 128)
        private String contactKey;

        @NotBlank
        @Size(max = 80)
        private String name;

        @NotBlank
        @Size(max = 300)
        private String contact;

        @Size(min = 1, max = 500)
        private String remark;
    }

    @Data
    public static class AttachmentLink {
        @NotBlank
        @Size(max = 128)
        private String attachmentId;

        @NotNull
        @Min(0)
        private Integer displayOrder;
    }

    @Data
    public static class RegistrationForm {
        @NotNull
        @Valid
        @Size(min = 1, max = 30)
        private List<FormField> fields;

        @NotNull
        private Boolean allowModification;
    }

    @Data
    public static class FormField {
        @NotBlank
        @Size(max = 128)
        private String fieldKey;

        @NotBlank
        @Size(max = 120)
        private String label;

        @NotBlank
        @Pattern(regexp = "NAME|STUDENT_NUMBER|CLASS|PHONE|CUSTOM")
        private String purpose;

        @NotBlank
        @Pattern(regexp = "TEXT|SINGLE_SELECT|MULTI_SELECT")
        private String type;

        @NotNull
        private Boolean required;

        @Size(min = 1, max = 500)
        private String helpText;

        @Min(1)
        @Max(2000)
        private Integer maxLength;

        @Valid
        @Size(min = 1, max = 100)
        private List<FormOption> options;

        @NotNull
        @Min(0)
        private Integer displayOrder;
    }

    @Data
    public static class FormOption {
        @NotBlank
        @Size(max = 128)
        private String optionKey;

        @NotBlank
        @Size(max = 120)
        private String label;
    }
}
