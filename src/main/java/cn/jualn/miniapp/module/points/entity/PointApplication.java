package cn.jualn.miniapp.module.points.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PointApplication {
    private Long id;
    private String personName;
    private String acceptingOrganizationName;
    private Long effectiveLedgerId;
    private Integer effectivePoints;
    private String personId;
    private String submittedBindingId;
    private Long applicantUserId;
    private String title;
    private String description;
    private String fileIds;
    private String status;
    private long revision;
    private Long reviewerUserId;
    private String reviewNote;
    private Integer awardedPoints;
    private Long grantLedgerId;
    private LocalDateTime createdAt;
    private LocalDateTime reviewedAt;
    private String acceptingOrganizationId;
    private String membershipId;
    private LocalDateTime closedAt;
    private Long closedByUserId;
    private String closureReason;
}
