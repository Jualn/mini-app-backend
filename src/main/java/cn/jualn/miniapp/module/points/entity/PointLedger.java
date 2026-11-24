package cn.jualn.miniapp.module.points.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PointLedger {
    private Long id;
    private String personId;
    private String submittedBindingId;
    private Long applicantUserId;
    private Long applicationId;
    private String kind;
    private int pointsDelta;
    private Long operatorUserId;
    private String reason;
    private String basis;
    private String correctionId;
    private Long originalLedgerId;
    private LocalDateTime createdAt;
    private String traceId;
    private String acceptingOrganizationId;
    private String membershipId;
}
