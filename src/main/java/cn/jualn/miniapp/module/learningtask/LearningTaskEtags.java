package cn.jualn.miniapp.module.learningtask;

import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A linked Article's availability is part of this representation, not a new Task state.
 */
public final class LearningTaskEtags {
    private LearningTaskEtags() {
    }

    public static String task(Task task) {
        return StrongEtag.of(resource(task.articleId(), task.articleAvailability()), task.taskId(), task.revision());
    }

    public static String preview(LearningTaskBO.Preview preview) {
        return StrongEtag.of("learning-preview-" + preview.canApply() + "-" + preview.alreadyAssignedCount(), preview.previewId(), 1);
    }

    public static String submission(LearningTaskBO.Submission submission) {
        return StrongEtag.of("learning-submission", submission.submissionId(), submission.revision());
    }

    public static String reviewResource(LearningTaskBO.Result result) {
        return reviewResource(result, new LearningTaskBO.SubmitterSummary("UNAVAILABLE", null));
    }

    public static String reviewResource(LearningTaskBO.Result result,
                                        LearningTaskBO.SubmitterSummary summary) {
        String value = summary.status() + ":" + summary.displayName();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "learning-review-" + assignmentResource(result.task(), result.assignment()) + "-a" + result.assignment().revision() + "-n" +
                    HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }


    public static String review(LearningTaskBO.Result result,
                                LearningTaskBO.SubmitterSummary summary) {
        return StrongEtag.of(reviewResource(result, summary), result.submission().submissionId(), result.submission().revision());
    }

    public static String review(LearningTaskBO.Result result) {
        return StrongEtag.of(reviewResource(result), result.submission().submissionId(), result.submission().revision());
    }

    public static String resource(Long articleId, String availability) {
        return articleId == null ? "learning-task" : "learning-task-a" + articleId + "-" + availability;
    }

    public static String assignmentResource(Task task, LearningTaskBO.Assignment assignment) {
        String representation = LearningTaskEtags.task(task) + ":" + assignment.displayStatus();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(representation.getBytes(StandardCharsets.UTF_8));
            return "learning-assignment-" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String assignment(Task task, LearningTaskBO.Assignment assignment) {
        return StrongEtag.of(assignmentResource(task, assignment), assignment.assignmentId(), assignment.revision());
    }
}
