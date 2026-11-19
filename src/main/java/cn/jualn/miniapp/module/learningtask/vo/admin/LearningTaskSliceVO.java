package cn.jualn.miniapp.module.learningtask.vo.admin;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.List;

public final class LearningTaskSliceVO {
    private LearningTaskSliceVO() {
    }

    public record Preview(String previewId, String taskId, String purpose, int resolvedCount, int newAssignmentCount,
                          int alreadyAssignedCount, String expiresAt, String createdAt, boolean canApply,
                          int blockedCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Assignment(String assignmentId, String taskId,
                             LearningTaskBO.Subject subject,
                             String allocationStatus, String progress, String displayStatus,
                             String assignedAt, String assignedBy, String confirmedAt, String firstSubmittedAt,
                             Boolean onTimeFirstSubmission,
                             String latestSubmissionId, String supplementDeadline, String completedAt, String revokedAt,
                             String revokedBy, String revocationReason, String confirmedByUserId,
                             String confirmedBindingId) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(String taskId, String title, String description, String status,
                       LearningTaskAdminVO.Article article,
                       LearningTaskAdminVO.Requirements requirements, boolean reviewRequired, String firstDeadline,
                       @JsonSerialize(using = LearningTaskCounterSerializer.class) long deadlineRevision,
                       String createdAt, String updatedAt, String createdBy, String publishedAt, String closedAt,
                       String cancelledAt,
                       String cancelledBy, String cancellationReason,
                       UnifiedModels.Target managementTarget) {
    }

    public record MyDetail(Task task, Assignment assignment) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Submission(String submissionId, String assignmentId, int attempt, String kind, String text,
                             List<String> fileIds,
                             String confirmedAt, String submittedAt, String reviewStatus, String reviewedAt,
                             String reviewedBy, String reviewReason, String submittedByUserId,
                             LearningTaskBO.Subject subject, String bindingId) {
    }

    public record SubmissionPage(List<Submission> items, int page, int pageSize, int totalItems) {
    }

    public record ReviewDetail(Task task, Assignment assignment, Submission submission,
                               LearningTaskBO.SubmitterSummary submitterSummary) {
        public ReviewDetail(Task task, Assignment assignment, Submission submission) {
            this(task, assignment, submission, new LearningTaskBO.SubmitterSummary("UNAVAILABLE", null));
        }
    }

    public record ReviewPage(List<ReviewDetail> items, int page, int pageSize, int totalItems) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MyPage(List<MyDetail> items, String nextCursor) {
    }

    public record AssignmentPage(List<Assignment> items, int page, int pageSize, int totalItems) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AudienceMember(LearningTaskBO.Subject subject,
                                 String availability, String deliveryUserId, String bindingId,
                                 boolean alreadyAssigned) {
    }

    public record PreviewMemberPage(List<AudienceMember> items, int page, int pageSize, int totalItems) {
    }

    public record Statistics(int activeAssignments, int completed, int notStarted, int pendingReview, int rejected,
                             int overdue,
                             int onTimeFirstSubmissions, int submissionCount, int resubmissionCount,
                             int historicalAssignments,
                             int revokedAssignments, int historicalCompleted, String asOf, Double completionRate) {
    }
}
