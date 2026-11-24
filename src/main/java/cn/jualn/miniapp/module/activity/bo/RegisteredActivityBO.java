package cn.jualn.miniapp.module.activity.bo;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
public record RegisteredActivityBO(Long activityId, String title, Integer category, String organizer,
        String primaryLocation, TimelineFactBO mainSchedule, Integer publishStatus, Integer lifecycleStatus,
        String publicAccess, String stageState, java.time.LocalDateTime evaluatedAt) { }
