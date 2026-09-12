package cn.jualn.miniapp.module.exam.bo;

import java.time.OffsetDateTime;
import java.util.List;

public record HomePublicMatterRemindersBO(
        OffsetDateTime evaluatedAt,
        Source source,
        List<PublicMatterReminderBO> items) {

    public HomePublicMatterRemindersBO {
        items = List.copyOf(items);
    }

    public enum Source {
        SUBSCRIPTIONS,
        DEFAULT
    }

    public record PublicMatterReminderBO(
            Long publicMatterId,
            String name,
            String nodeName,
            OffsetDateTime reminderAt) {
    }
}
