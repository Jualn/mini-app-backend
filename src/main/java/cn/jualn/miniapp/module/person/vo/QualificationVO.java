package cn.jualn.miniapp.module.person.vo;

import cn.jualn.miniapp.module.person.bo.QualificationBO;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.Instant;
import java.util.List;

public final class QualificationVO {
    private QualificationVO() {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Import(String importId,String organizationId,String sourceReference,String state,List<QualificationBO.Column> columns,String version,Instant expiresAt,Integer rowCount,Integer invalidRowCount,Integer importedCount,Mapping mapping,String namespace,String fileStatus,Instant fileRetainUntil,String planId,Instant planExpiresAt) {}
    public record Mapping(int sheetIndex, int headerRow, MappingColumns columns, List<QualificationBO.StageValue> stageValues) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MappingColumns(int realName, int studentNumber, Integer majorClass, int stageId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String rosterEntryId,String organizationId,String realName,String studentNumberMasked,String majorClass,String expectedStageId,String baselineStatus,String sourceReference,String version,String personId,String accountBindingState,PersonVO.Identity personIdentity) {
        @com.fasterxml.jackson.annotation.JsonProperty public String sourceEffect() {return "TRACE_ONLY";}
    }
    public record PreviewPage(java.util.List<QualificationBO.PreviewRow> items,int page,int pageSize,@JsonSerialize(using=PersonCountSerializer.class) long totalItems,Instant evaluatedAt,String importId,String version,int sheetIndex,int headerRow,String planId,Instant planExpiresAt,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) QualificationBO.RosterReviewPage review) {}
    public record WorkbookSheets(String importId,String version,List<QualificationBO.Sheet> sheets) {}
    public record HeaderColumns(String importId,String version,int sheetIndex,int headerRow,List<QualificationBO.HeaderColumn> columns) {}
    public record Application(String applicationId,String applicantUserId,String status,Instant submittedAt,String version,String verificationMethod,Instant decidedAt,String personId,String bindingId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mentor(String personId,String realName,String position,String affiliation,String personStatus,String bindingState,String accountAvailability,String qualificationStatus,String qualificationId,Instant validFrom,Instant validUntil,Instant verifiedAt,String verifiedByUserId,String verificationReference,String version,Instant evaluatedAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CertifiedMentor(String userId,String realName,String position,String affiliation,boolean certified,Instant evaluatedAt,Instant validUntil,String personId,String bindingId,String qualificationId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Audit(String changeId,String resourceId,String operation,String operatorUserId,Instant recordedAt,String reason,String verificationReference,String correctsChangeId,String verificationMethod,String sourceReference) {}
    public record Answer(String postId,String commentId,Instant createdAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MyIdentity(String userId,String identityVerification,String presence,PersonVO.IdentityRecord currentRecord,String effectiveStatus,Instant evaluatedAt,Instant verifiedAt,String verificationMethod,String personId,String bindingId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreationRecovery(String state,String importId) {}
    public record Page<T>(List<T> items,int page,int pageSize,@JsonSerialize(using=PersonCountSerializer.class) long totalItems,Instant evaluatedAt) {}
}
