package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.module.learningtask.entity.LearningAssignment;
import java.time.LocalDateTime;
import java.util.List;

/** The read projection and the locked write use the same completion conditions. */
public final class LearningTaskClosure {
    private LearningTaskClosure() {}
    public record Decision(boolean allowed, String reason, LocalDateTime recheckAt) {}

    public static Decision evaluate(String status, LocalDateTime deadline, List<LearningAssignment> assignments, LocalDateTime now) {
        if (!"PUBLISHED".equals(status)) return new Decision(false, "NOT_PUBLISHED", null);
        if (now.isBefore(deadline)) return new Decision(false, "BEFORE_DEADLINE", deadline);
        LocalDateTime supplement = null;
        for (var assignment : assignments) {
            if (!"ACTIVE".equals(assignment.getAllocationStatus())) continue;
            if ("PENDING_REVIEW".equals(assignment.getProgress())) return new Decision(false, "PENDING_REVIEW", null);
            var until = assignment.getSupplementDeadline();
            if ("REJECTED".equals(assignment.getProgress()) && until != null && now.isBefore(until)
                    && (supplement == null || until.isAfter(supplement))) supplement = until;
        }
        return supplement == null ? new Decision(true, "READY", null) : new Decision(false, "SUPPLEMENT_OPEN", supplement);
    }
}
