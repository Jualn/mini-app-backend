package cn.jualn.miniapp.module.activity.dto.admin;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class AdminActivitySaveRequest {
    private com.fasterxml.jackson.databind.JsonNode formSchema;
    private Integer registrationLimit;
    private Boolean clearRegistrationLimit;
    private Integer startPrecision;
    private Integer endPrecision;
    private String timeDescription;
    private Integer registrationStartPrecision;
    private Integer registrationEndPrecision;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private java.time.LocalDateTime registrationStart;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private java.time.LocalDateTime registrationEnd;

    @Valid @Size(max=30)
    private List<cn.jualn.miniapp.module.activity.dto.request.EventSectionRequest> sections;
    @Valid @Size(max=20)
    private List<cn.jualn.miniapp.module.activity.dto.request.EventActionRequest> actions;
    @Size(max=300) private String summary;
    @Size(max=255) private String audienceSummary;
    private Integer registrationMode;
    private Integer participantMode;
    private Integer capacityUnit;
    @Positive private Integer capacity;
    @Size(max=512) private String coverObjectKey;
    private Boolean clearCover;
    private Long coverAttachmentId;



    @NotBlank(message = "活动标题不能为空")
    @Size(max = 128, message = "活动标题不能超过128字")
    private String title;

    @Size(max = 10000, message = "活动详情不能超过10000字")
    private String content;

    @Size(max = 255, message = "活动地点不能超过255字")
    private String location;

    @NotBlank(message = "活动分类不能为空")
    @Pattern(regexp = "other|culture|volunteer|ideology|lecture|sports", message = "活动分类不合法")
    private String category;

    @NotBlank(message = "主办单位不能为空")
    @Size(max = 128, message = "主办单位不能超过128字")
    private String organizer;

    @Size(max = 6, message = "参与范围不能超过6项")
    private List<@Pattern(regexp = "college|information|science|finance|humanities|foundation",
            message = "参与范围不合法") String> audienceCodes;

    @Size(max = 64, message = "联系人姓名不能超过64字")
    private String contactName;

    @Size(max = 32, message = "联系电话不能超过32字")
    private String contactPhone;

    @Size(max = 255, message = "参与方式不能超过255字")
    private String joinMethod;

    @Size(max = 512, message = "二维码地址不能超过512字")
    private String qrcodeUrl;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private LocalDateTime endTime;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
    private LocalDateTime enrollDeadline;

    @Positive(message = "人数上限必须大于0")
    private Integer maxParticipants;

    @Valid
    @Size(max = 20, message = "最多添加20个时间线节点")
    private List<TimelineItem> timeline = List.of();

    @Valid
    @Size(max = 9, message = "最多添加9个附件")
    private List<AttachmentItem> attachments = List.of();

    @Data
    public static class TimelineItem {
    private String nodeType;
    private String location;
    private Integer startPrecision;
    private Integer endPrecision;
    private String timeDescription;
    private Long id;


        @NotBlank(message = "时间线节点名称不能为空")
        @Size(max = 64, message = "时间线节点名称不能超过64字")
        private String label;

        @Size(max = 255, message = "时间线说明不能超过255字")
        private String description;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        private LocalDateTime startTime;

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        private LocalDateTime endTime;
    }

    @Data
    public static class AttachmentItem {

        @NotBlank(message = "附件类型不能为空")
        @Pattern(regexp = "link|image|pdf|word", message = "附件类型不合法")
        private String typeCode;

        @Size(max = 512, message = "附件对象键不能超过512字")
        private String objectKey;

        @Size(max = 512, message = "附件地址不能超过512字")
        private String url;

        @Size(max = 255, message = "附件名称不能超过255字")
        private String name;
    }
}
