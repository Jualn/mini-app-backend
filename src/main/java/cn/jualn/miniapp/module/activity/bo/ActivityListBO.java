package cn.jualn.miniapp.module.activity.bo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityListBO {

    private Long id;
    private String title;
    private String location;
    private ActivityCategory category;
    private String organizer;
    private Integer maxParticipants;
    private Integer audienceScope;
    private LocalDateTime enrollDeadline;
    private ActivityStatus status;
    private LocalDateTime publishedAt;
}
