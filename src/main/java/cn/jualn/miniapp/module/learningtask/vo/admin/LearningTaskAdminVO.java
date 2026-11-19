package cn.jualn.miniapp.module.learningtask.vo.admin;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.List;

/**
 * Canonical wire types: explicit string IDs, numeric counters, RFC3339 strings, absent optional fields.
 */
public final class LearningTaskAdminVO {
    private LearningTaskAdminVO() {
    }

    public record Requirements(boolean confirmationRequired, int minimumTextLength, int minimumFiles,
                               int maximumFiles, List<String> allowedFileKinds) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Filter(List<String> organizationIds, List<String> identityIds, List<String> developmentStageIds) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Persons(List<String> personIds, List<Filter> filters, String authorizedScopeId) {
    }

    public record Accounts(List<String> userIds) {
    }

    public record Audience(Persons persons, Accounts accounts) {
    }

    public record Article(String articleId, String availability) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(String taskId, String title, String description, String status, Article article,
                         Requirements requirements, boolean reviewRequired, String firstDeadline,
                         @JsonSerialize(using = LearningTaskCounterSerializer.class) long deadlineRevision,
                         Audience audience,
                         String createdAt, String updatedAt, String createdBy, String publishedAt, String closedAt,
                         String cancelledAt, String cancelledBy, String cancellationReason,
                         UnifiedModels.Target managementTarget) {
    }

    public record Page(List<Detail> items, int page, int pageSize,
                       @JsonSerialize(using = LearningTaskCounterSerializer.class) long totalItems) {
    }
}
