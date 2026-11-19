package cn.jualn.miniapp.module.person.entity;

import java.time.LocalDateTime;
import lombok.Data;

public final class QualificationRows {
    private QualificationRows() {}
    @Data public static class Import {
        private String id,organizationId,sourceReference,fileFingerprint,columnsJson,mappingJson,state,lastEventId;
        private byte[] workbook,preview;
        private long creator,revision;
        private Long approvedBy;
        private Integer rowCount,invalidRowCount,importedCount;
        private LocalDateTime expiresAt,planExpiresAt,fileRetainUntil;
        private String namespace;
        private String planId,planFingerprint;
    }
    @Data public static class Entry {
        private String id,importId,organizationId,studentDigest,expectedStageId,baselineStatus,sourceReference,lastEventId;
        private byte[] privateValues;
        private String personId;
        private Long linkedUserId;
        private long revision,sourceApprover;
    }
    @Data public static class Application {
        private String id,rosterEntryId,organizationId,lastEventId,personId,bindingId;
        private long applicant,sourceVersion;
        private LocalDateTime submittedAt;
    }
    @Data public static class Mentor {
        private String personId;
        private long revision;
        private String realName,position,affiliation,qualificationId,qualificationState,verificationReference,lastEventId;
        private String personStatus,bindingState,accountAvailability;
        private LocalDateTime validFrom,validUntil,verifiedAt;
        private Long verifiedBy;
    }
}
