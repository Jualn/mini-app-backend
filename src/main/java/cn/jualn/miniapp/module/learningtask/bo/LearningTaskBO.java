package cn.jualn.miniapp.module.learningtask.bo;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Internal snapshots; HTTP representations are deliberately a separate boundary.
 */
public final class LearningTaskBO {
    private LearningTaskBO() {
    }

    public enum Status {DRAFT, PUBLISHED, CLOSED, CANCELLED}

    public enum Allocation {ACTIVE, REVOKED}

    public enum Progress {NOT_STARTED, PENDING_REVIEW, REJECTED, COMPLETED}

    public enum ReviewStatus {NOT_REQUIRED, PENDING_REVIEW, APPROVED, REJECTED}

    public enum Purpose {PUBLISH, ADD}

    public enum Decision {APPROVE, REJECT}

    public enum FileKind {IMAGE, VIDEO, PDF, WORD}

    public enum Action {
        CREATE, READ, EDIT, PREVIEW, PUBLISH, ADD, DEADLINE, CANCEL, CLOSE,
        ASSIGNMENT_READ, REVOKE, REVOKE_PARTICIPATED, REVIEW_READ, REVIEW, STATISTICS, MY_READ, CONFIRM, SUBMIT
    }

    public record Requirements(boolean confirmationRequired, int minimumTextLength, int minimumFiles,
                               int maximumFiles, List<FileKind> allowedFileKinds) {
        public Requirements {
            allowedFileKinds = allowedFileKinds == null ? List.of() : List.copyOf(allowedFileKinds);
        }

        public boolean confirmationOnly() {
            return minimumTextLength == 0 && minimumFiles == 0;
        }
    }

    public record Filter(List<String> organizationIds, List<String> identityIds, List<String> developmentStageIds) {
        public Filter {
            organizationIds = copy(organizationIds);
            identityIds = copy(identityIds);
            developmentStageIds = copy(developmentStageIds);
        }
    }

    public record OrganizationSelection(String organizationId, List<String> personIds) {
        public OrganizationSelection { personIds = copy(personIds); }
    }

    public record Audience(List<String> personIds, List<String> accountUserIds, List<Filter> filters,
                           String authorizedScopeId,
                           @JsonInclude(JsonInclude.Include.NON_EMPTY) List<OrganizationSelection> organizationSelections) {
        public Audience(List<String> personIds, List<String> accountUserIds, List<Filter> filters, String authorizedScopeId) {
            this(personIds, accountUserIds, filters, authorizedScopeId, List.of());
        }
        public Audience {
            personIds = copy(personIds);
            accountUserIds = copy(accountUserIds);
            filters = copy(filters);
            organizationSelections = copy(organizationSelections);
        }
    }

    public record Save(String title, String description, Long articleId, Requirements requirements,
                       boolean reviewRequired, LocalDateTime firstDeadline, Audience audience,
                       UnifiedModels.Target managementTarget) {
        public Save(String title, String description, Long articleId, Requirements requirements, boolean reviewRequired, LocalDateTime firstDeadline, Audience audience) {
            this(title, description, articleId, requirements, reviewRequired, firstDeadline, audience, null);
        }
    }

    /**
     * Presence flags preserve omitted versus explicit null for the only nullable patch field.
     */
    public record Edit(String title, String description, boolean articlePresent, Long articleId,
                       Requirements requirements, Boolean reviewRequired, LocalDateTime firstDeadline,
                       Audience audience) {
    }

    public record Material(String text, List<Long> fileIds, Boolean confirmed) {
        public Material {
            fileIds = copy(fileIds);
        }
    }

    public record PreviewRequest(Purpose purpose, Audience audience) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subject(String type, String personId, String userId) {
        public String key() {
            return type + ":" + ("PERSON".equals(type) ? personId : userId);
        }

        public static Subject person(String id) {
            return new Subject("PERSON", id, null);
        }

        public static Subject account(long id) {
            return new Subject("ACCOUNT", null, Long.toString(id));
        }
    }

    public record Recipient(Subject subject, Long deliveryUserId, String bindingId, String availability) {
    }

    public record AudienceMember(Subject subject, String availability, Long deliveryUserId, String bindingId,
                                 boolean alreadyAssigned) {
        public Recipient recipient() {
            return new Recipient(subject, deliveryUserId, bindingId, availability);
        }
    }

    public record PreviewMemberPage(List<AudienceMember> items, int page, int pageSize, int totalItems) {
    }

    public record Apply(long previewId, String reason) {
    }

    public record Deadline(LocalDateTime firstDeadline, String reason) {
    }

    public record Revoke(String reason, boolean allowParticipated) {
    }

    public record Review(Decision decision, String reason) {
    }

    public record Task(long taskId, String title, String description, Long articleId, Status status,
                       Requirements requirements, boolean reviewRequired, LocalDateTime firstDeadline,
                       long deadlineRevision, Audience audience, long revision, long createdBy,
                       LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime publishedAt,
                       LocalDateTime closedAt, LocalDateTime cancelledAt, Long cancelledBy, String cancellationReason,
                       String articleAvailability,
                       UnifiedModels.Target managementTarget) {
    }

    /**
     * Explicit IDs or a proven LEARNING_TASK/GLOBAL grant; absence never implies global access.
     */
    public record ReadScope(List<Long> taskIds, boolean namespaceGlobal) {
        public ReadScope {
            taskIds = List.copyOf(java.util.Objects.requireNonNull(taskIds));
        }

        public ReadScope(List<Long> taskIds) {
            this(taskIds, false);
        }

        public static ReadScope globalNamespace() {
            return new ReadScope(List.of(), true);
        }
    }

    public record AdminQuery(int page, int pageSize, String q, Status status) {
    }

    public record AdminPage(List<Task> items, int page, int pageSize, long totalItems) {
        public AdminPage {
            items = List.copyOf(items);
        }
    }

    public record Assignment(long assignmentId, long taskId, Long userId, Allocation allocationStatus,
                             Progress progress, String displayStatus, long revision, long assignedBy,
                             LocalDateTime assignedAt, LocalDateTime confirmedAt, LocalDateTime firstSubmittedAt,
                             Boolean onTimeFirstSubmission, Long latestSubmissionId, LocalDateTime supplementDeadline,
                             LocalDateTime completedAt, LocalDateTime revokedAt, Long revokedBy,
                             String revocationReason, Subject subject, Long confirmedByUserId,
                             String confirmedBindingId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubmitterSummary(String status, String displayName) {
    }

    public record Submission(long submissionId, long assignmentId, int attempt, String text, List<Long> fileIds,
                             LocalDateTime confirmedAt, LocalDateTime submittedAt, ReviewStatus reviewStatus,
                             LocalDateTime reviewedAt, Long reviewedBy, String reviewReason, long revision,
                             long submittedByUserId, Subject subject, String bindingId) {
        public Submission {
            fileIds = copy(fileIds);
        }
    }

    public record Preview(long previewId, long taskId, Purpose purpose, List<AudienceMember> members,
                          int newAssignmentCount, int alreadyAssignedCount, LocalDateTime createdAt,
                          LocalDateTime expiresAt, int blockedCount, boolean applicable) {
        public Preview {
            members = copy(members);
        }

        public Preview(long previewId, long taskId, Purpose purpose, List<AudienceMember> members, int newAssignmentCount, int alreadyAssignedCount, LocalDateTime createdAt, LocalDateTime expiresAt, int blockedCount) {
            this(previewId, taskId, purpose, members, newAssignmentCount, alreadyAssignedCount, createdAt, expiresAt, blockedCount, true);
        }

        public boolean canApply() {
            return applicable && blockedCount == 0 && newAssignmentCount > 0;
        }
    }

    public record Result(Task task, Assignment assignment, Submission submission, Preview preview,
                         int addedCount, int alreadyAssignedCount) {
    }

    /**
     * Frozen, non-sensitive business event. Adapter must durably accept in the caller's DB transaction.
     */
    public record Notice(String occurrence, String type, long taskId, long assignmentId, Long userId,
                         String title, LocalDateTime beforeDeadline, LocalDateTime afterDeadline,
                         LocalDateTime supplementDeadline, Subject subject, String bindingId, String availability) {
        public Notice(String occurrence, String type, long taskId, long assignmentId, long userId, String title, LocalDateTime beforeDeadline, LocalDateTime afterDeadline, LocalDateTime supplementDeadline, Subject subject, String bindingId) {
            this(occurrence, type, taskId, assignmentId, userId, title, beforeDeadline, afterDeadline, supplementDeadline, subject, bindingId, "AVAILABLE");
        }
    }

    private static <T> List<T> copy(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
