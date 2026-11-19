package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.bo.RegisteredActivityBO;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ActivityPublicHistory {
    private final ActivityMapper mapper;
    private final TimelineService timeline;
    private final ObjectMapper json;
    public record PublicFacts(String title, Integer category, String organizer, String primaryLocation, TimelineFactBO main) { }

    @Transactional(propagation = Propagation.MANDATORY)
    public void remember(Activity activity) {
        if (!Integer.valueOf(1).equals(activity.getPublishStatus()) || activity.getDeletedAt() != null) return;
        var facts = facts(activity);
        try {
            if (mapper.savePublicSummary(activity.getId(), json.writeValueAsString(facts)) != 1)
                throw new IllegalStateException("Public activity summary update failed");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Public activity summary serialization failed", invalid);
        }
    }

    public RegisteredActivityBO read(Activity activity, LocalDateTime now) {
        PublicFacts facts = null;
        if (Integer.valueOf(1).equals(activity.getPublishStatus()) && activity.getDeletedAt() == null) {
            facts = facts(activity);
        } else if (activity.getLastPublicSummary() != null) {
            try { facts = json.readValue(activity.getLastPublicSummary(), PublicFacts.class); }
            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new IllegalStateException("Invalid retained public activity summary", invalid);
            }
        }
        return new RegisteredActivityBO(activity.getId(), facts == null ? "活动信息暂不可用" : facts.title(),
                facts == null ? null : facts.category(), facts == null ? null : facts.organizer(),
                facts == null ? null : facts.primaryLocation(), facts == null ? null : facts.main(),
                activity.getPublishStatus(), activity.getLifecycleStatus(),
                Integer.valueOf(1).equals(activity.getPublishStatus()) && activity.getDeletedAt() == null ? "AVAILABLE" : "UNAVAILABLE",
                ActivityStagePolicy.state(activity.getLifecycleStatus(), timeline.listTimelinesByTarget(TargetType.ACTIVITY, activity.getId()), now), now);
    }
    private PublicFacts facts(Activity activity) {
        var main = ActivityStagePolicy.main(timeline.listTimelinesByTarget(TargetType.ACTIVITY, activity.getId()));
        // The retained node includes only the schedule facts used by the history projection.
        var minimal = main == null ? null : TimelineFactBO.builder().nodeType("ACTIVITY_START")
                .startTime(main.getStartTime()).endTime(main.getEndTime()).startPrecision(main.getStartPrecision())
                .endPrecision(main.getEndPrecision()).build();
        return new PublicFacts(activity.getTitle(), activity.getCategory(), activity.getOrganizer(), activity.getLocation(), minimal);
    }
}
