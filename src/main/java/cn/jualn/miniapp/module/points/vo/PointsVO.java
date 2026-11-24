package cn.jualn.miniapp.module.points.vo;

import cn.jualn.miniapp.module.points.bo.PointsBO;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class PointsVO {
    private PointsVO() {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AcceptanceBasis(String status,String organizationId,String membershipId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Application(String applicationId,String applicantUserId,String title,String description,List<String> fileIds,
            String status,String createdAt,String reviewerUserId,String reviewedAt,String reviewNote,Integer awardedPoints,String grantLedgerId,
            AcceptanceBasis acceptanceBasis,String closedAt,String closedByUserId,String closureReason,String personId,String submittedBindingId,String personName,String acceptingOrganizationName,Accounting accounting,Management management) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Accounting(String status,int effectivePoints,String effectiveLedgerId) {}
    public record Correction(String correctionId,String applicationId,String personId,String originalLedgerId,String operatorUserId,String reason,String basis,String createdAt,Ledger reversal,Ledger replacement) {}
    public static Correction correction(PointsBO.Correction c) {return new Correction(c.correctionId(),id(c.applicationId()),c.personId(),id(c.originalLedgerId()),id(c.operatorUserId()),c.reason(),c.basis(),time(c.createdAt()),ledger(c.reversal()),ledger(c.replacement()));}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ledger(String ledgerId,String personId,String applicationId,String kind,int pointsDelta,String operatorUserId,
            String reason,String originalLedgerId,String createdAt,AcceptanceBasis acceptanceBasis,String applicantUserId,String submittedBindingId,String correctionId,String basis) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Management(List<String> operations,String reviewUnavailableReason,String approvalUnavailableReason) {}
    public record ManagementLedger(Ledger ledger,Application application) {}
    public record AcceptingOrganization(String organizationId,String name,String membershipId) {}
    public record AcceptingOrganizations(String evaluatedAt,List<AcceptingOrganization> items) {}
    public record Summary(String personId,@JsonSerialize(using=PointsCounterSerializer.class) long balance,
            @JsonSerialize(using=PointsCounterSerializer.class) long totalGranted,@JsonSerialize(using=PointsCounterSerializer.class) long totalReversed,String evaluatedAt) {}
    public record Page<T>(List<T> items,int page,int pageSize,@JsonSerialize(using=PointsCounterSerializer.class) long totalItems) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Eligibility(boolean eligible,String evaluatedAt,String reason) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PersonStatistics(String personId,
            @JsonSerialize(using=PointsCounterSerializer.class) long balance,@JsonSerialize(using=PointsCounterSerializer.class) long totalGranted,
            @JsonSerialize(using=PointsCounterSerializer.class) long totalReversed,@JsonSerialize(using=PointsCounterSerializer.class) long applicationCount,
            @JsonSerialize(using=PointsCounterSerializer.class) long pendingCount,@JsonSerialize(using=PointsCounterSerializer.class) long approvedCount,
            @JsonSerialize(using=PointsCounterSerializer.class) long rejectedCount,@JsonSerialize(using=PointsCounterSerializer.class) long closedCount,String personName) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrganizationStatistics(String organizationId,@JsonSerialize(using=PointsCounterSerializer.class) long participantCount,
            @JsonSerialize(using=PointsCounterSerializer.class) long balance,@JsonSerialize(using=PointsCounterSerializer.class) long totalGranted,
            @JsonSerialize(using=PointsCounterSerializer.class) long totalReversed,String organizationName) {}
    public record StatisticsPage<T>(List<T> items,int page,int pageSize,@JsonSerialize(using=PointsCounterSerializer.class) long totalItems,String membershipBasis,String evaluatedAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AcceptanceStatistics(String organizationId,@JsonSerialize(using=PointsCounterSerializer.class) long applicationCount,
            @JsonSerialize(using=PointsCounterSerializer.class) long balance,@JsonSerialize(using=PointsCounterSerializer.class) long totalGranted,
            @JsonSerialize(using=PointsCounterSerializer.class) long totalReversed,String organizationName) {}
    public record AcceptanceStatisticsPage(List<AcceptanceStatistics> items,int page,int pageSize,@JsonSerialize(using=PointsCounterSerializer.class) long totalItems,String basis,String evaluatedAt) {}
    public static PersonStatistics personStatistics(PointsBO.PersonStatistics row) {
        return new PersonStatistics(row.personId(),row.balance(),row.totalGranted(),row.totalReversed(),row.applicationCount(),row.pendingCount(),row.approvedCount(),row.rejectedCount(),row.closedCount(),row.personName());
    }
    public static OrganizationStatistics organizationStatistics(PointsBO.OrganizationStatistics row) {
        return new OrganizationStatistics(row.organizationId(),row.participantCount(),row.balance(),row.totalGranted(),row.totalReversed(),row.organizationName());
    }
    public static Application application(PointsBO.Application app) {
        return new Application(id(app.applicationId()),id(app.applicantUserId()),app.title(),app.description(),app.fileIds(),app.status().name(),time(app.createdAt()),
                id(app.reviewerUserId()),time(app.reviewedAt()),app.reviewNote(),app.awardedPoints(),id(app.grantLedgerId()),
                basis(app.acceptingOrganizationId(),app.membershipId()),time(app.closedAt()),id(app.closedByUserId()),app.closureReason(),app.personId(),app.submittedBindingId(),app.personName(),app.acceptingOrganizationName(),app.accounting()==null?null:new Accounting(app.accounting().status(),app.accounting().effectivePoints(),id(app.accounting().effectiveLedgerId())),app.management()==null?null:new Management(app.management().operations(),app.management().reviewUnavailableReason(),app.management().approvalUnavailableReason()));
    }
    public static Ledger ledger(PointsBO.Ledger row) {
        return new Ledger(id(row.ledgerId()),row.personId(),id(row.applicationId()),row.kind().name(),row.pointsDelta(),id(row.operatorUserId()),row.reason(),id(row.originalLedgerId()),time(row.createdAt()),basis(row.acceptingOrganizationId(),row.membershipId()),id(row.applicantUserId()),row.submittedBindingId(),row.correctionId(),row.basis());
    }
    private static AcceptanceBasis basis(String organization,String membership) {
        return organization==null?new AcceptanceBasis("LEGACY_UNRESOLVED",null,null):new AcceptanceBasis("BOUND",organization,membership);
    }
    public static Summary summary(PointsBO.Summary row) { return new Summary(row.personId(),row.balance(),row.totalGranted(),row.totalReversed(),time(row.evaluatedAt())); }
    public static String id(Long value) { return value==null?null:value.toString(); }
    public static String time(LocalDateTime value) { return value==null?null:java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atOffset(ZoneOffset.UTC)); }
}
