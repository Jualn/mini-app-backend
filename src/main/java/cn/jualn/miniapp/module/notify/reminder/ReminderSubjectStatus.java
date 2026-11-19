package cn.jualn.miniapp.module.notify.reminder;

/** Business-owned eligibility check used by the generic fan-out worker. */
public interface ReminderSubjectStatus {
    int sourceType();
    default boolean isCurrentAndEligible(ReminderExecution intent, java.time.LocalDateTime now) {
        return isCurrentAndEligible(intent.sourceId(), intent.generation(), intent.timelineId(), now);
    }
    default boolean lockCurrentAndEligible(ReminderExecution intent, java.time.LocalDateTime now) {
        return isCurrentAndEligible(intent, now);
    }
    default boolean lockBusinessEventEligible(long sourceId, Integer notificationType) { return true; }
    boolean isCurrentAndEligible(long sourceId, Long generation, Long timelineId,
                                 java.time.LocalDateTime now);
}
