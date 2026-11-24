package cn.jualn.miniapp.module.activity.service;

import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;
import java.time.LocalDateTime;
import java.util.List;

/** Evaluates the one authoritative main schedule without persisting a second lifecycle. */
public final class ActivityStagePolicy {
    private ActivityStagePolicy() { }

    public static TimelineFactBO main(List<TimelineFactBO> timeline) {
        var matches = (timeline == null ? List.<TimelineFactBO>of() : timeline).stream()
                .filter(node -> "ACTIVITY_START".equals(node.getNodeType())).toList();
        if (matches.size() != 1) return null;
        var node = matches.get(0);
        if (node.getStartTime() == null || !List.of(1, 2).contains(node.getStartPrecision() == null ? 0 : node.getStartPrecision())) return null;
        if (node.getEndTime() == null && node.getEndPrecision() != null && node.getEndPrecision() != 0) return null;
        if (node.getEndTime() != null && (!node.getStartPrecision().equals(node.getEndPrecision())
                || node.getEndTime().isBefore(node.getStartTime())
                || node.getStartPrecision() == 2 && node.getEndTime().equals(node.getStartTime()))) return null;
        return node;
    }

    public static String state(Integer lifecycle, List<TimelineFactBO> timeline, LocalDateTime now) {
        if (Integer.valueOf(2).equals(lifecycle)) return "CANCELLED";
        if (Integer.valueOf(1).equals(lifecycle)) return "ENDED";
        var main = main(timeline);
        if (main == null) return "UNKNOWN";
        if (Integer.valueOf(1).equals(main.getStartPrecision())) {
            if (now.toLocalDate().isBefore(main.getStartTime().toLocalDate())) return "NOT_STARTED";
            if (main.getEndTime() != null && now.toLocalDate().isAfter(main.getEndTime().toLocalDate())) return "ENDED";
        } else {
            if (now.isBefore(main.getStartTime())) return "NOT_STARTED";
            if (main.getEndTime() != null && !now.isBefore(main.getEndTime())) return "ENDED";
        }
        return "ONGOING";
    }
}
