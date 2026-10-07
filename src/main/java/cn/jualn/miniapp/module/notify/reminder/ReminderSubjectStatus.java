package cn.jualn.miniapp.module.notify.reminder;

/** Business-owned eligibility check used by the generic fan-out worker. */
public interface ReminderSubjectStatus {
    int sourceType();
    boolean isCurrentAndEligible(long sourceId, Long generation, Long timelineId,
                                 java.time.LocalDateTime now);
}
