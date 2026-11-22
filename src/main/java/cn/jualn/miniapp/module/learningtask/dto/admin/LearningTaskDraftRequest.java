package cn.jualn.miniapp.module.learningtask.dto.admin;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Closed canonical requests, independently strict from the legacy application's JSON defaults.
 */
public final class LearningTaskDraftRequest {
    private LearningTaskDraftRequest() {
    }

    public record Requirements(boolean confirmationRequired, int minimumTextLength, int minimumFiles,
                               int maximumFiles, List<String> allowedFileKinds) {
        public Requirements {
            allowedFileKinds = List.copyOf(allowedFileKinds);
        }
    }

    public record Filter(List<String> organizationIds, List<String> identityIds, List<String> developmentStageIds) {
    }

    public record OrganizationSelection(String organizationId, List<String> personIds) {}

    public record Persons(List<String> personIds, List<Filter> filters, String authorizedScopeId,
                          List<OrganizationSelection> organizationSelections) {
        public Persons(List<String> personIds, List<Filter> filters, String authorizedScopeId) {
            this(personIds, filters, authorizedScopeId, null);
        }
    }

    public record Accounts(List<String> userIds) {
    }

    public record Audience(Persons persons, Accounts accounts) {
    }

    @JsonDeserialize(using = LearningTaskDraftJson.CreateDeserializer.class)
    public record Create(String title, String description, String articleId, Requirements requirements,
                         boolean reviewRequired, OffsetDateTime firstDeadline, Audience audience,
                         UnifiedModels.Target managementTarget) {
    }

    @JsonDeserialize(using = LearningTaskDraftJson.EditDeserializer.class)
    public record Edit(String title, String description, boolean articlePresent, String articleId,
                       Requirements requirements, Boolean reviewRequired, OffsetDateTime firstDeadline,
                       UnifiedModels.Target managementTarget, Audience audience) {
    }
}
