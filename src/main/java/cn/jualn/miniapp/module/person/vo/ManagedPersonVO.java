package cn.jualn.miniapp.module.person.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Namespace;

public final class ManagedPersonVO {
    private ManagedPersonVO() {}
    public record Profile(String personId, String realName, String status, String version, String createdAt, String updatedAt, String lastChangeId) {}
    @JsonInclude(JsonInclude.Include.NON_NULL) public record Provenance(String sourceKind, String sourceReference, String confirmedByUserId, String confirmedAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL) public record Registration(String personId, String majorClass, String lastAction, Provenance provenance, String version) {}
    @JsonInclude(JsonInclude.Include.NON_NULL) public record Binding(String bindingId, String userId, String personId, String status, String version,
            String startedAt, String verificationMethod, String sourceReference, String startedByUserId, String endedAt, String endedByUserId, String endReason) {}
    public record BindingChange(List<String> endedBindingIds, Binding binding) {}
    @JsonInclude(JsonInclude.Include.NON_NULL) public record PersonSlot(String state, String personId, Binding binding, String version) {}
    @JsonInclude(JsonInclude.Include.NON_NULL) public record MyBinding(String state, String userId, String personId, String bindingId, String personStatus, String version, String evaluatedAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL) public record Audit(String changeId, String personId, String operation, String operatorUserId, String recordedAt,
            String reason, String verificationReference, String aggregateKind, String resourceId, String beforeVersion, String afterVersion) {}
    public record AuditPage(List<Audit> items, int page, int pageSize,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=PersonCountSerializer.class) long totalItems, String evaluatedAt) {}
    public record BindingPage(List<Binding> items, int page, int pageSize,
            @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=PersonCountSerializer.class) long totalItems, String evaluatedAt) {}
    public record NamespaceList(List<Namespace> items) {}
}
