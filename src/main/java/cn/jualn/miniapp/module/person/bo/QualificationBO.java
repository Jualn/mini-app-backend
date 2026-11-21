package cn.jualn.miniapp.module.person.bo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Fixed Contract values. Private workbook values enter only the creator-authorized preview; ordinary reads and receipts remain masked. */
public final class QualificationBO {
    private QualificationBO() {}
    public record Columns(int realName,int studentNumber,int majorClass,int stageId) {}
    public record StageValue(String cellValue,String stageId) {}
    public record Mapping(int sheetIndex,int headerRow,Columns columns,List<StageValue> stageValues) {}
    public record Column(int sheetIndex,int columnIndex,String header) {}
    public record Sheet(int sheetIndex,String name,boolean hidden) {}
    public record WorkbookSheets(String importId,String version,List<Sheet> sheets) {}
    public record HeaderColumn(int columnIndex,String header,String cellType,boolean usable) {}
    public record HeaderColumns(String importId,String version,int sheetIndex,int headerRow,List<HeaderColumn> columns) {}
    public record Commit(String reason,String verificationReference,Boolean confirmSource,String planId,List<Integer> skipRows,
                         @JsonInclude(JsonInclude.Include.NON_NULL) ReviewDecision review) {
        public Commit(String reason,String verificationReference,Boolean confirmSource,String planId,List<Integer> skipRows) {
            this(reason, verificationReference, confirmSource, planId, skipRows, null);
        }
        public Commit(String reason,String verificationReference,Boolean confirmSource,String planId) {
            this(reason, verificationReference, confirmSource, planId, List.of());
        }
    }
    public record ReviewDecision(List<MissingMemberDecision> decisions) {}
    public record MissingMemberDecision(String membershipId, String action, String basis) {}
    public record MissingMember(String membershipId, String realName) {}
    public record RosterReviewPage(List<MissingMember> missingMembers, long totalMissingMembers) {}
    public record Correction(String realName,String studentNumber,String majorClass,String expectedStageId,
                             String baselineStatus,String reason,String verificationReference) {}
    public record Apply(String realName,String studentNumber) {}
    public record ProfileWrite(String position,String affiliation,String reason,String verificationReference) {}
    public record QualificationChange(String action,Instant validUntil,String reason,String verificationReference) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Import(String importId,String organizationId,String sourceReference,String state,List<Column> columns,
                         String version,Instant expiresAt,Integer rowCount,Integer invalidRowCount,Integer importedCount,Mapping mapping,String namespace,String fileStatus,Instant fileRetainUntil,String planId,Instant planExpiresAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RowError(String field,String code,Integer columnIndex,String reason,String detail) {
        public RowError(String field,String code) {this(field,code,null,null,null);}
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PreviewRow(int rowNumber,String realName,String studentNumber,String majorClass,String stageId,
                             List<RowError> errors,Resolution resolution,PersonnelMatch personnelMatch) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PersonnelMatch(String outcome, java.util.Map<String, Object> current, String issue,
                                 String personId, List<String> editableFields, List<PersonnelCandidate> candidates, String membershipId) {
        public PersonnelMatch(String outcome, java.util.Map<String, Object> current, String issue) {
            this(outcome, current, issue, null, null, null, null);
        }
    }
    public record PersonnelCandidate(String personId, String realName, String membershipId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportSource(String realName, String studentNumberMasked, String majorClass, String stageId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportEffect(int sequence, String organizationId, List<String> actions, String matchOutcome,
                               String decision, String issue, ImportSource source, String membershipId, String basis,
                               java.util.List<String> correctedFields) {
        public ImportEffect(int sequence, String organizationId, List<String> actions, String matchOutcome,
                            String decision, String issue, ImportSource source, String membershipId, String basis) {
            this(sequence, organizationId, actions, matchOutcome, decision, issue, source, membershipId, basis, null);
        }
        public ImportEffect(int sequence, String organizationId, List<String> actions, String matchOutcome,
                            String decision, String issue, ImportSource source) {
            this(sequence, organizationId, actions, matchOutcome, decision, issue, source, null, null);
        }
    }
    public record ImportTotals(int created, int reused, int corrected, int joined, int removed, int unchanged, int skipped) {}
    public record ImportResults(List<ImportEffect> items, int page, int pageSize, int totalItems, ImportTotals totals, @JsonInclude(JsonInclude.Include.NON_NULL) String rosterConclusion) {
        public ImportResults(List<ImportEffect> items, int page, int pageSize, int totalItems, ImportTotals totals) {
            this(items, page, pageSize, totalItems, totals, null);
        }
    }
    public record OrganizationImport(String importId, String sourceReference, Instant committedAt, int importedCount) {}
    /** Encrypted persistence payload only. */
    @JsonInclude(JsonInclude.Include.NON_NULL) public record Resolution(String visibility,String outcome,String membershipAction,String personAction,String personId,String sourceAction) {}
    public record PrivateValues(String realName,String studentNumber,String majorClass) {}
    public record ParsedRow(int rowNumber,PrivateValues values,String stageId,List<RowError> errors) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String rosterEntryId,String organizationId,String realName,String studentNumberMasked,String majorClass,
                        String expectedStageId,String baselineStatus,String sourceReference,String version,String personId,String accountBindingState,
                        PersonBO.Identity personIdentity) {
        @com.fasterxml.jackson.annotation.JsonProperty public String sourceEffect() {return "TRACE_ONLY";}
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"organizationId"})
    public record Application(String applicationId,String applicantUserId,String status,Instant submittedAt,
                              String version,String verificationMethod,Instant decidedAt,String personId,String bindingId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mentor(String personId,String realName,String position,String affiliation,String personStatus,String bindingState,String accountAvailability,String qualificationStatus,String qualificationId,
                         Instant validFrom,Instant validUntil,Instant verifiedAt,String verifiedByUserId,String verificationReference,String version,Instant evaluatedAt,List<String> allowedOperations) {
        public Mentor(String personId,String realName,String position,String affiliation,String personStatus,String bindingState,String accountAvailability,String qualificationStatus,String qualificationId,
                      Instant validFrom,Instant validUntil,Instant verifiedAt,String verifiedByUserId,String verificationReference,String version,Instant evaluatedAt) {
            this(personId,realName,position,affiliation,personStatus,bindingState,accountAvailability,qualificationStatus,qualificationId,validFrom,validUntil,verifiedAt,verifiedByUserId,verificationReference,version,evaluatedAt,null);
        }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CertifiedMentor(String userId,String realName,String position,String affiliation,boolean certified,Instant evaluatedAt,Instant validUntil,String personId,String bindingId,String qualificationId) {}
    public record Audit(String changeId,String resourceId,String operation,String operatorUserId,Instant recordedAt,String reason,
                        String verificationReference,String correctsChangeId,String verificationMethod,String sourceReference) {}
    public record Answer(String postId,String commentId,Instant createdAt) {}
    public record Page<T>(List<T> items,int page,int pageSize,long totalItems,Instant evaluatedAt) {}
    public record PreviewPage(List<PreviewRow> items,int page,int pageSize,long totalItems,Instant evaluatedAt,String importId,String version,int sheetIndex,int headerRow,String planId,Instant planExpiresAt,RosterReviewPage review) {}
    public record Resource<T>(T value,String etag,String location,int status) {}
    public record EntryQuery(String organizationId,String linkState,String stageId,String baselineStatus,String q,String identityVerification) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MyIdentity(String userId,String identityVerification,String presence,PersonBO.IdentityRecord currentRecord,String effectiveStatus,
                             Instant evaluatedAt,Instant verifiedAt,String verificationMethod,String personId,String bindingId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreationRecovery(String state,String importId) {}
    public record UploadFingerprint(String organizationId,String sourceReference,String fileFingerprint) {}
    public record UploadReservation(UploadFingerprint command,Resource<Import> completed) {}
}
