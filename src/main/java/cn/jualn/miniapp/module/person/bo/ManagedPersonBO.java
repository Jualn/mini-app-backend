package cn.jualn.miniapp.module.person.bo;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Independent subject projections. A personId is never a login or an RBAC grantee.
 */
public final class ManagedPersonBO {
    private ManagedPersonBO() {
    }

    public record Create(String realName, String majorClass, String reason, String verificationReference) {
    }

    public record NameChange(String realName, String reason, String verificationReference) {
    }

    public record Lifecycle(String action, String reason, String verificationReference) {
    }

    public record MajorClassPatch(boolean present, String majorClass, String reason, String verificationReference) {
    }

    public record Profile(String personId, String realName, String status, String version,
                          LocalDateTime createdAt, LocalDateTime updatedAt, String lastChangeId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Provenance(String sourceKind, String sourceReference, String confirmedByUserId,
                             LocalDateTime confirmedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Registration(String personId, String majorClass, String lastAction, Provenance provenance,
                               String version) {
    }

    public record Namespace(String identifierType, String namespace, String displayName) {
    }

    public record IdentifierCreate(String identifierType, String namespace, String value, String verification,
                                   String reason, String verificationReference, String personVersion) {
    }

    public record IdentifierChange(String action, String value, String reason, String verificationReference) {
    }

    public record Identifier(String identifierId, String personId, String identifierType, String namespace,
                             String valueMasked, String verification, String status, String version,
                             String sourceReference) {
    }

    public record IdentifierValue(String identifierId, String personId, String value, String version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Binding(String bindingId, String userId, String personId, String status, String version,
                          LocalDateTime startedAt, String verificationMethod, String sourceReference,
                          String startedByUserId, LocalDateTime endedAt, String endedByUserId, String endReason) {
    }

    public record BindingVersion(String bindingId, String version) {
    }

    public record Bind(String personId, String userId, String personVersion, String personBindingVersion,
                       String userBindingVersion, String reason, String verificationReference) {
    }

    public record Reassociate(List<BindingVersion> endedBindings, String personId, String userId,
                              String personVersion, String personBindingVersion, String userBindingVersion,
                              String reason, String verificationReference) {
        public Reassociate {
            endedBindings = List.copyOf(endedBindings);
        }
    }

    public record End(String reason, String verificationReference) {
    }

    public record BindingChange(List<String> endedBindingIds, Binding binding) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PersonSlot(String state, String personId, Binding binding, String version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AccountSlot(String state, String userId, String personId, String bindingId, String version) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MyBinding(String state, String userId, String personId, String bindingId, String personStatus,
                            String version, LocalDateTime evaluatedAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Audit(String changeId, String personId, String operation, String operatorUserId,
                        LocalDateTime recordedAt, String reason, String verificationReference,
                        String aggregateKind, String resourceId, String beforeVersion, String afterVersion) {
    }

    public record AuditPage(List<Audit> items, int page, int pageSize, long totalItems, LocalDateTime evaluatedAt) {
    }
}
