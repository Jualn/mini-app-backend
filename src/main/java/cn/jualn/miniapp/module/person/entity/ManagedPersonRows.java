package cn.jualn.miniapp.module.person.entity;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * Persistence owned by the independent Person command owner.
 */
public final class ManagedPersonRows {
    private ManagedPersonRows() {
    }

    @Data
    public static class Profile {
        private String id;
        private String realName;
        private String status;
        private long revision;
        private long bindingRevision;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
        private String lastChangeId;
    }

    @Data
    public static class Registration {
        private String personId;
        private String majorClass;
        private String lastAction;
        private String sourceKind;
        private String sourceReference;
        private Long confirmedBy;
        private LocalDateTime confirmedAt;
        private long revision;
    }

    @Data
    public static class Identifier {
        private String id;
        private String personId;
        private String identifierType;
        private String namespace;
        private String valueMasked;
        private byte[] privateValue;
        private String verification;
        private String status;
        private long revision;
        private String sourceReference;
    }

    @Data
    public static class Binding {
        private String id;
        private long userId;
        private String personId;
        private String status;
        private long revision;
        private LocalDateTime startedAt;
        private String verificationMethod;
        private String sourceReference;
        private long startedBy;
        private LocalDateTime endedAt;
        private Long endedBy;
        private String endReason;
    }

    @Data
    public static class ProtectedValue {
        private byte[] privateValue;
    }
}
