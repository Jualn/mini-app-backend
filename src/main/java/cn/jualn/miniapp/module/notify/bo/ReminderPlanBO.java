package cn.jualn.miniapp.module.notify.bo;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

/** Immutable reminder facts exposed to business source and eligibility implementations. */
@Value
@Builder
public class ReminderPlanBO {
    Long id;
    Integer sourceType;
    Long sourceId;
    Long timelineId;
    String ruleKey;
    Long generation;
    String recipientScope;
    Integer notifyType;
    String title;
    String content;
    LocalDateTime subjectStartsAt;
    String subjectLocation;
    LocalDateTime sendAt;
    Integer status;
}
