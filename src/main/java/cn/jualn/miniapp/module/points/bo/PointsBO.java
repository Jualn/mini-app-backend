package cn.jualn.miniapp.module.points.bo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class PointsBO {
    private PointsBO() {}
    public enum Action { MY_READ, CREATE, ELIGIBILITY, READ, MATERIAL_READ, REVIEW, REVERSE, CORRECT, CLOSE }
    public enum Status { PENDING, APPROVED, REJECTED, CLOSED }
    public enum Kind { GRANT, CORRECTION_GRANT, REVERSAL }
    public record Create(String title,String description,List<String> fileIds,String acceptingOrganizationId) {
        public Create { fileIds=List.copyOf(fileIds); }
        public Create(String title,String description,List<String> fileIds) {this(title,description,fileIds,null);}
    }
    public record Adjustment(int correctedPoints,String reason,String basis) {}
    public record Reversal(String reason,String basis) {}
    public record Accounting(String status,int effectivePoints,Long effectiveLedgerId) {}
    public record Correction(String correctionId,long applicationId,String personId,long originalLedgerId,long operatorUserId,
            String reason,String basis,LocalDateTime createdAt,Ledger reversal,Ledger replacement) {}
    public record Review(Status decision,Integer awardedPoints,String reviewNote) {}
    public record Application(long applicationId,long applicantUserId,String title,String description,List<String> fileIds,
            Status status,LocalDateTime createdAt,Long reviewerUserId,LocalDateTime reviewedAt,String reviewNote,Integer awardedPoints,Long grantLedgerId,
            String acceptingOrganizationId,String membershipId,LocalDateTime closedAt,Long closedByUserId,String closureReason,String personId,String submittedBindingId,String personName,String acceptingOrganizationName,Accounting accounting,Management management) {
        public Application(long applicationId,long applicantUserId,String title,String description,List<String> fileIds,Status status,LocalDateTime createdAt,Long reviewerUserId,LocalDateTime reviewedAt,String reviewNote,Integer awardedPoints,Long grantLedgerId,String acceptingOrganizationId,String membershipId,LocalDateTime closedAt,Long closedByUserId,String closureReason,String personId,String submittedBindingId,String personName,String acceptingOrganizationName,Accounting accounting) {
            this(applicationId,applicantUserId,title,description,fileIds,status,createdAt,reviewerUserId,reviewedAt,reviewNote,awardedPoints,grantLedgerId,acceptingOrganizationId,membershipId,closedAt,closedByUserId,closureReason,personId,submittedBindingId,personName,acceptingOrganizationName,accounting,null);
        }
        public Application managed(Management value) {
            return new Application(applicationId,applicantUserId,title,description,fileIds,status,createdAt,reviewerUserId,reviewedAt,reviewNote,awardedPoints,grantLedgerId,acceptingOrganizationId,membershipId,closedAt,closedByUserId,closureReason,personId,submittedBindingId,personName,acceptingOrganizationName,accounting,value);
        }
        public Application(long applicationId,long applicantUserId,String title,String description,List<String> fileIds,Status status,LocalDateTime createdAt,Long reviewerUserId,LocalDateTime reviewedAt,String reviewNote,Integer awardedPoints,Long grantLedgerId,String acceptingOrganizationId,String membershipId,LocalDateTime closedAt,Long closedByUserId,String closureReason,String personId,String submittedBindingId) {
            this(applicationId,applicantUserId,title,description,fileIds,status,createdAt,reviewerUserId,reviewedAt,reviewNote,awardedPoints,grantLedgerId,acceptingOrganizationId,membershipId,closedAt,closedByUserId,closureReason,personId,submittedBindingId,null,null,null);
        }
        public Application(long applicationId,long applicantUserId,String title,String description,List<String> fileIds,Status status,LocalDateTime createdAt,
                Long reviewerUserId,LocalDateTime reviewedAt,String reviewNote,Integer awardedPoints,Long grantLedgerId) {
            this(applicationId,applicantUserId,title,description,fileIds,status,createdAt,reviewerUserId,reviewedAt,reviewNote,awardedPoints,grantLedgerId,null,null,null,null,null,null,null);
        }
    }
    public record Ledger(long ledgerId,long applicantUserId,long applicationId,Kind kind,int pointsDelta,long operatorUserId,
            String reason,Long originalLedgerId,LocalDateTime createdAt,String acceptingOrganizationId,String membershipId,String personId,String submittedBindingId,String correctionId,String basis) {
        public Ledger(long ledgerId,long applicantUserId,long applicationId,Kind kind,int pointsDelta,long operatorUserId,String reason,Long originalLedgerId,LocalDateTime createdAt,String acceptingOrganizationId,String membershipId,String personId,String submittedBindingId) {
            this(ledgerId,applicantUserId,applicationId,kind,pointsDelta,operatorUserId,reason,originalLedgerId,createdAt,acceptingOrganizationId,membershipId,personId,submittedBindingId,null,null);
        }
        public Ledger(long ledgerId,long applicantUserId,long applicationId,Kind kind,int pointsDelta,long operatorUserId,String reason,Long originalLedgerId,LocalDateTime createdAt) {
            this(ledgerId,applicantUserId,applicationId,kind,pointsDelta,operatorUserId,reason,originalLedgerId,createdAt,null,null,null,null);
        }
    }
    public record Management(List<String> operations,String reviewUnavailableReason,String approvalUnavailableReason) {
        public Management(List<String> operations,String reviewUnavailableReason) {this(operations,reviewUnavailableReason,null);}
        public Management { operations=List.copyOf(operations); }
    }
    public record ManagementLedger(Ledger ledger,Application application) {}
    public record ManagementOrganization(String organizationId,String name) {}
    public record Summary(String personId,long balance,long totalGranted,long totalReversed,LocalDateTime evaluatedAt) {}
    public record Page<T>(List<T> items,int page,int pageSize,long totalItems) {}
    public record Eligibility(boolean eligible,LocalDateTime evaluatedAt,String reason) {
        public Eligibility(boolean eligible,LocalDateTime evaluatedAt) {this(eligible,evaluatedAt,eligible?null:"BUSINESS_STAGE_INELIGIBLE");}
    }
    public record AcceptingOrganization(String organizationId,String name,String membershipId) {}
    public record AcceptingOrganizations(LocalDateTime evaluatedAt,List<AcceptingOrganization> items) {}
    public record Subject(String personId,String bindingId) {}
    public record Acceptance(String organizationId,String membershipId) {}
    public record Qualification(String organizationId,String verification,Boolean enabled,String evidenceId,String evidenceOrganizationId) {}
    public record Query(int page,int pageSize,Status status,Kind kind,Long applicationId,String search) {
        public Query(int page,int pageSize,Status status,Kind kind,Long applicationId) {this(page,pageSize,status,kind,applicationId,null);}
        public Query(int page,int pageSize,Status status,Kind kind) {this(page,pageSize,status,kind,null);}
        public long getOffset() { return ((long)page-1)*pageSize; }
    }
    public record RequestScope(long actor,String session,String operation,long target,String key) {}
    public record Receipt(String fingerprint,String resultJson) {}
    public record AuthorizationAudit(long actorUserId,long targetUserId,String operationName,long applicationId,Long ledgerId,
            String requestKey,String txContextId,long authorityEpoch,LocalDateTime evaluatedAt,LocalDateTime checkedAt,String policySource) {}
    public record Totals(BigDecimal granted,BigDecimal reversed) {}
    public record ParticipantTotals(String personId,BigDecimal granted,BigDecimal reversed,long applicationCount,long pendingCount,long approvedCount,long rejectedCount,long closedCount,String personName) {}
    public record OrganizationTotals(String organizationId,long participantCount,BigDecimal granted,BigDecimal reversed) {}
    public record AcceptanceTotals(String organizationId,long applicationCount,BigDecimal granted,BigDecimal reversed) {}
    public record AcceptanceStatistics(String organizationId,long applicationCount,long balance,long totalGranted,long totalReversed,String organizationName) {
        public AcceptanceStatistics(String organizationId,long applicationCount,long balance,long totalGranted,long totalReversed) {this(organizationId,applicationCount,balance,totalGranted,totalReversed,null);}
    }
    public record PersonStatistics(String personId,long balance,long totalGranted,long totalReversed,long applicationCount,long pendingCount,long approvedCount,long rejectedCount,long closedCount,String personName) {
        public PersonStatistics(String personId,long balance,long totalGranted,long totalReversed,long applicationCount,long pendingCount,long approvedCount,long rejectedCount,long closedCount) {this(personId,balance,totalGranted,totalReversed,applicationCount,pendingCount,approvedCount,rejectedCount,closedCount,null);}
    }
    public record OrganizationStatistics(String organizationId,long participantCount,long balance,long totalGranted,long totalReversed,String organizationName) {
        public OrganizationStatistics(String organizationId,long participantCount,long balance,long totalGranted,long totalReversed) {this(organizationId,participantCount,balance,totalGranted,totalReversed,null);}
    }
    public record StatisticsPage<T>(List<T> items,int page,int pageSize,long totalItems,String membershipBasis,LocalDateTime evaluatedAt) {}
}
