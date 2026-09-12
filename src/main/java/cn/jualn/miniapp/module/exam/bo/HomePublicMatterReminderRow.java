package cn.jualn.miniapp.module.exam.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Database projection for one eligible homepage reminder. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HomePublicMatterReminderRow {
    private Long publicMatterId;
    private String name;
    private Long timeNodeId;
    private String nodeName;
    private LocalDateTime reminderAt;
    private String source;
}
