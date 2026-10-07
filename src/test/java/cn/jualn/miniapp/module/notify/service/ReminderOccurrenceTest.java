package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReminderOccurrenceTest {
    @Test void canonicalMissingAnchorCannotFallbackToExecutionIdentity() {
        var plan = NotifyPlan.builder().id(1L).sourceType(1).sourceId(42L).notifyType(8)
                .sendAt(LocalDateTime.of(2030, 1, 1, 14, 0)).build();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> ReminderOccurrence.sourceKey(plan, 7));
    }
    @Test void rebuiltPlanOffsetAndReasonDoNotChangeBusinessIdentity() {
        for (int type = 8; type <= 11; type++) {
            NotifyPlan plan = NotifyPlan.builder().id(1L).sourceType(type <= 9 ? 1 : 2).sourceId(42L)
                    .notifyType(type).subjectStartsAt(LocalDateTime.of(2026, 10, 1, 15, 0))
                    .sendAt(LocalDateTime.of(2026, 10, 1, 14, 0)).recipientScope("SUBSCRIBERS").build();
            String original = ReminderOccurrence.sourceKey(plan, 7);
            plan.setId(999L);
            plan.setSendAt(plan.getSendAt().minusMinutes(30));
            plan.setRecipientScope("SUBSCRIBERS_NOT_REGISTERED");
            assertEquals(original, ReminderOccurrence.sourceKey(plan, 7));
            plan.setSubjectStartsAt(plan.getSubjectStartsAt().plusHours(2));
            assertNotEquals(original, ReminderOccurrence.sourceKey(plan, 7));
            assertNotEquals(ReminderOccurrence.sourceKey(plan, 7), ReminderOccurrence.sourceKey(plan, 8));
        }
    }
}
