package cn.jualn.miniapp.module.exam.bo;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class AdminPublicEventListBO {
    private Long id;
    private String title;
    private String summary;
    private Integer category;
    private Integer eventType;
    private String editionLabel;
    private Integer publishStatus;
    private LocalDateTime startTime;
    private Integer startPrecision;
    private LocalDateTime endTime;
    private Integer endPrecision;
    private Integer registrationMode;
    private LocalDateTime updatedAt;
}
