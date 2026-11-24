package cn.jualn.miniapp.module.activity.vo;
import com.fasterxml.jackson.annotation.JsonInclude;
import cn.jualn.miniapp.module.timeline.vo.TimelineScheduleVO;
public record MyActivityRegistrationItemVO(RegisteredActivitySummary activity, PersonalRegistrationVO registration, String registrationEtag) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegisteredActivitySummary(String activityId, String title, String category, String organizer,
            String primaryLocation, TimelineScheduleVO mainSchedule, String publishStatus, String lifecycleStatus,
            String publicAccess, ActivitySummaryVO.Availability stage) { }
}
