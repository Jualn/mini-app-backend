package cn.jualn.miniapp.module.activity.service;

import java.time.LocalDateTime;
import java.util.List;
import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Public participation hint; writes still arbitrate against locked authoritative state. */
@Component
@RequiredArgsConstructor
public class ActivityParticipationPolicy {

    public String evaluate(Integer publication, Integer lifecycle, Integer mode,
            List<TimelineFactBO> timeline,
            Integer capacity, Long submitted, LocalDateTime now) {
        return evaluate(publication, lifecycle, mode, timeline, capacity, submitted, now, false);
    }

    public String evaluate(Integer publication, Integer lifecycle, Integer mode, List<TimelineFactBO> timeline,
            Integer capacity, Long submitted, LocalDateTime now, boolean continuous) {
        String stage = ActivityStagePolicy.state(lifecycle, timeline, now);
        if ("ENDED".equals(stage) || "CANCELLED".equals(stage)) return "UNAVAILABLE";
        if (!Integer.valueOf(1).equals(publication) || !Integer.valueOf(0).equals(lifecycle)
                ) {
            return "UNAVAILABLE";
        }
        if (Integer.valueOf(1).equals(mode)) {
            return "NO_REGISTRATION";
        }
        if (platform(mode) && !continuous && "ONGOING".equals(stage)) return "CLOSED";
        Window window = window(timeline);
        if (window.start() != null && now.isBefore(window.start())) {
            return "NOT_OPEN";
        }
        if (window.end() != null && !now.isBefore(window.end())) {
            return "CLOSED";
        }
        if (Integer.valueOf(3).equals(mode)) {
            return "EXTERNAL";
        }
        if (!platform(mode) || !window.platformValid()) {
            return "UNAVAILABLE";
        }
        return capacity != null && submitted != null && submitted >= capacity ? "FULL" : "OPEN";
    }

    public boolean canCancel(Integer lifecycle, boolean deleted, boolean continuous,
            List<TimelineFactBO> timeline, LocalDateTime now) {
        if (deleted || !Integer.valueOf(0).equals(lifecycle)) return false;
        String stage = ActivityStagePolicy.state(lifecycle, timeline, now);
        if ("ENDED".equals(stage) || "CANCELLED".equals(stage)) return false;
        if (!continuous && ActivityStagePolicy.main(timeline) != null) return "NOT_STARTED".equals(stage);
        var end = window(timeline).end();
        return end != null && now.isBefore(end);
    }

    public boolean platform(Integer mode) {
        return Integer.valueOf(2).equals(mode) || Integer.valueOf(4).equals(mode);
    }

    public Window window(List<TimelineFactBO> timeline) {
        List<TimelineFactBO> starts = exactPoints(timeline, "REGISTRATION_START");
        List<TimelineFactBO> ends = exactPoints(timeline, "REGISTRATION_END");
        long startCount = count(timeline, "REGISTRATION_START");
        long endCount = count(timeline, "REGISTRATION_END");
        boolean standardStartAmbiguous = startCount > 1;
        boolean standardEndAmbiguous = endCount != 1;
        LocalDateTime start = startCount == 1 && starts.size() == 1 ? starts.get(0).getStartTime() : null;
        LocalDateTime end = endCount == 1 && ends.size() == 1 ? ends.get(0).getStartTime() : null;
        boolean contradictory = start != null && end != null && !start.isBefore(end);
        if (contradictory) {
            start = null;
            end = null;
        }
        boolean valid = !standardStartAmbiguous && !standardEndAmbiguous
                && starts.size() == startCount
                && ends.size() == 1 && !contradictory;
        return new Window(start, end, valid);
    }

    private List<TimelineFactBO> exactPoints(List<TimelineFactBO> timeline, String type) {
        return safe(timeline).stream().filter(item -> type.equals(item.getNodeType()))
                .filter(item -> Integer.valueOf(2).equals(item.getStartPrecision()) && item.getStartTime() != null
                        && item.getEndTime() == null)
                .toList();
    }

    private long count(List<TimelineFactBO> timeline, String type) {
        return safe(timeline).stream().filter(item -> type.equals(item.getNodeType())).count();
    }

    private List<TimelineFactBO> safe(List<TimelineFactBO> timeline) {
        return timeline == null ? List.of() : timeline;
    }

    public record Window(LocalDateTime start, LocalDateTime end, boolean platformValid) {
    }
}
