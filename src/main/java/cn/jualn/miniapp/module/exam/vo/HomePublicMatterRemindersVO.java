package cn.jualn.miniapp.module.exam.vo;

import java.time.OffsetDateTime;
import java.util.List;

public record HomePublicMatterRemindersVO(
        OffsetDateTime evaluatedAt,
        String source,
        List<PublicMatterReminderVO> items) {

    public HomePublicMatterRemindersVO {
        items = List.copyOf(items);
    }

    public record PublicMatterReminderVO(
            String publicMatterId,
            String name,
            String nodeName,
            OffsetDateTime reminderAt) {
    }
}
