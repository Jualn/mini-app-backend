package cn.jualn.miniapp.module.exam.dto.admin;
import lombok.Data;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.List;
import cn.jualn.miniapp.module.exam.dto.request.EventSectionRequest;
import cn.jualn.miniapp.module.exam.dto.request.EventActionRequest;

/** 运维完整表单；sections/actions/attachments/timeline 省略保留，显式数组替换。 */
@Data
public class AdminPublicEventSaveRequest {
    @NotBlank @Size(max=128) private String title;
    @Size(max=300) private String summary;
    @NotNull @Min(0) @Max(7) private Integer category;
    @NotNull @Min(0) @Max(3) private Integer eventType;
    @Size(max=128) private String editionLabel;
    @Size(max=128) private String organizer;
    @Size(max=255) private String location;
    @NotNull @Min(0) @Max(63) private Integer audienceScope;
    @Size(max=255) private String audienceSummary;
    @Size(max=64) private String contactName;
    @Size(max=32) private String contactPhone;
    @NotNull @Min(0) @Max(3) private Integer registrationMode;
    @NotNull @Min(0) @Max(3) private Integer participantMode;
    @Positive private Integer capacity;
    @Min(1) @Max(2) private Integer capacityUnit;
    @Positive private Long coverAttachmentId;
    @Size(max=512) private String coverObjectKey;
    private Boolean clearCover;
    @Size(max=255) private String timeDescription;
    @Size(max=20000) private String content;
    @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm[:ss]") private LocalDateTime startTime;
    @NotNull @Min(0) @Max(2) private Integer startPrecision;
    @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm[:ss]") private LocalDateTime endTime;
    @NotNull @Min(0) @Max(2) private Integer endPrecision;
    @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm[:ss]") private LocalDateTime registrationStart;
    @NotNull @Min(0) @Max(2) private Integer registrationStartPrecision;
    @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm[:ss]") private LocalDateTime registrationEnd;
    @NotNull @Min(0) @Max(2) private Integer registrationEndPrecision;
    @Valid @Size(max=30) private List<EventSectionRequest> sections;
    @Valid @Size(max=20) private List<EventActionRequest> actions;
    @Valid @Size(max=9) private List<Attachment> attachments;
    @Valid @Size(max=20) private List<Timeline> timeline;
    @Data public static class Attachment {
        @NotNull private cn.jualn.miniapp.common.enums.MediaType type;
        @Size(max=512) private String objectKey;
        @Size(max=512) private String url;
        @Size(max=255) private String originalName;
        @Min(0) @Max(127) private Integer sortOrder;
    }
    @Data public static class Timeline {
        @Positive private Long id;
        @NotBlank @Size(max=64) private String label;
        @Size(max=255) private String description;
        @Size(max=32) private String nodeType;
        @Size(max=255) private String location;
        @Size(max=255) private String timeDescription;
        @Min(0) @Max(2) private Integer startPrecision;
        @Min(0) @Max(2) private Integer endPrecision;
        @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm[:ss]") private LocalDateTime startTime;
        @JsonFormat(pattern="yyyy-MM-dd'T'HH:mm[:ss]") private LocalDateTime endTime;
    }
}
