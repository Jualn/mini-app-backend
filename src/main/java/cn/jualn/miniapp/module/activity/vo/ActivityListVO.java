package cn.jualn.miniapp.module.activity.vo;

import cn.jualn.miniapp.common.enums.ActivityCategory;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动列表项响应对象。
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Data
@Builder
public class ActivityListVO {

    private Long id;
    private String title;
    private String location;
    private ActivityStatus status;
    private ActivityCategory category;
    private String organizer;
    private Integer maxParticipants;
    private Integer audienceScope;
    private LocalDateTime enrollDeadline;
    private LocalDateTime publishedAt;
}
