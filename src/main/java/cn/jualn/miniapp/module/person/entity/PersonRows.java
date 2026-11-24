package cn.jualn.miniapp.module.person.entity;

import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * Internal persistence only; all writes are owned by PersonFoundationService.
 */
public final class PersonRows {
    private PersonRows() {
    }

    @lombok.Data
    public static class RosterCompleteness {
        private String organizationId, status, confirmedMembershipVersion, sourceReference, lastChangeId;
        private Long confirmedBy;
        private java.time.LocalDateTime confirmedAt;
        private long revision;
    }

    @Data
    public static class Head {
        private String personId;
        private long revision;
        private String identityVerification;
        private String identityPresence;
        private String currentRecordId;
        private String stageId;
        private String identityStatus;
        private LocalDateTime identityValidFrom;
        private LocalDateTime identityValidUntil;
        private Long identityVerifiedBy;
        private LocalDateTime identityVerifiedAt;
        private String membershipVerification;
        private Long membershipVerifiedBy;
        private LocalDateTime membershipVerifiedAt;
        private String lastChangeId;
        private String identityChangeId;
        private String membershipChangeId;
    }

    @Data
    public static class Organization {
        private String id;
        private String name;
        private String description;
        private String type;
        private String status;
        private long revision;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String lastChangeId;
    }

    @Data
    public static class Membership {
        private String id;
        private String personId;
        private String organizationId;
        private LocalDateTime validFrom;
        private LocalDateTime validUntil;
        private String createdEventId;
        private String endedEventId;
    }

    @Data
    public static class Scope {
        private String id;
        private long granteeUserId;
        private String mode;
        private String domain;
        private String actions;
        private String targetType;
        private String targetId;
        private LocalDateTime validFrom;
        private LocalDateTime validUntil;
        private LocalDateTime revokedAt;
        private Long revokedBy;
        private String revokeReason;
        private String source;
        private String authorityGrantId;
        private long grantedBy;
        private String reason;
        private String verificationReference;
        private long revision;
        private String lastEventId;
        private Integer currentSlot;
    }

    @Data
    public static class LocalMembership {
        private String personId;
        private String organizationId;
        private String presence;
        private String membershipId;
        private long verifiedBy;
        private LocalDateTime verifiedAt;
        private String lastEventId;
    }

    @Data
    public static class Event {
        private String personId;
        private String id;
        private String aggregateType;
        private String aggregateId;
        private long aggregateVersion;
        private String operation;
        private String beforeJson;
        private String afterJson;
        private long operatorUserId;
        private LocalDateTime recordedAt;
        private String reason;
        private String verificationReference;
        private String source;
        private String correctsEventId;
        private String receiptId;
        private String traceId;
        private String localOrganizationId;
        private String localBeforeJson;
        private String localAfterJson;
        private String verificationMethod;
        private String sourceReference;
        private Long sourceVersion;
        private Long sourceApprover;
    }

    @Data
    public static class Receipt {
        private String id;
        private long actor;
        private String operation;
        private String target;
        private String commandKey;
        private String fingerprint;
        private String resultJson;
        private String etag;
        private String location;
        private int status;
        private String eventId;
        private LocalDateTime createdAt;
    }
}
