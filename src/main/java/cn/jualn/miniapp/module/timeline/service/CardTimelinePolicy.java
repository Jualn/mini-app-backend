package cn.jualn.miniapp.module.timeline.service;

import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Selects the canonical read-only card projection from an owning resource's timeline. */
public final class CardTimelinePolicy {
    private static final Comparator<TimelineItemDTO> DISPLAY_ORDER = Comparator
            .comparingInt(CardTimelinePolicy::displayOrder)
            .thenComparing(CardTimelinePolicy::nodeKey);

    private CardTimelinePolicy() {
    }

    public static TimelineItemDTO select(List<TimelineItemDTO> timeline, LocalDateTime now) {
        Objects.requireNonNull(now, "selection time");
        List<TimelineItemDTO> nodes = timeline == null ? List.of() : timeline;

        TimelineItemDTO current = nodes.stream()
                .filter(node -> current(node, now))
                .min(DISPLAY_ORDER)
                .orElse(null);
        if (current != null) {
            return current;
        }

        LocalDate today = now.toLocalDate();
        TimelineItemDTO future = nodes.stream()
                .filter(node -> future(node, now, today))
                .min(Comparator.comparing(CardTimelinePolicy::startDate)
                        .thenComparingInt(CardTimelinePolicy::precisionOrder)
                        .thenComparing(CardTimelinePolicy::exactStart,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(DISPLAY_ORDER))
                .orElse(null);
        if (future != null) {
            return future;
        }

        return nodes.stream()
                .filter(CardTimelinePolicy::text)
                .min(DISPLAY_ORDER)
                .orElse(null);
    }

    private static boolean current(TimelineItemDTO node, LocalDateTime now) {
        if (exact(node)) {
            LocalDateTime start = node.getStartTime();
            LocalDateTime end = node.getEndTime();
            if (end != null && end.isAfter(start)) {
                return !now.isBefore(start) && now.isBefore(end);
            }
            return now.equals(start);
        }
        if (date(node)) {
            LocalDate today = now.toLocalDate();
            LocalDate start = node.getStartTime().toLocalDate();
            LocalDate end = node.getEndTime() == null ? start : node.getEndTime().toLocalDate();
            return !today.isBefore(start) && !today.isAfter(end);
        }
        return false;
    }

    private static boolean future(TimelineItemDTO node, LocalDateTime now, LocalDate today) {
        return exact(node) && node.getStartTime().isAfter(now)
                || date(node) && node.getStartTime().toLocalDate().isAfter(today);
    }

    private static boolean exact(TimelineItemDTO node) {
        return node != null && Integer.valueOf(2).equals(node.getStartPrecision())
                && node.getStartTime() != null;
    }

    private static boolean date(TimelineItemDTO node) {
        return node != null && Integer.valueOf(1).equals(node.getStartPrecision())
                && node.getStartTime() != null;
    }

    private static boolean text(TimelineItemDTO node) {
        return node != null && Integer.valueOf(0).equals(node.getStartPrecision())
                && node.getTimeDescription() != null && !node.getTimeDescription().isBlank();
    }

    private static LocalDate startDate(TimelineItemDTO node) {
        return node.getStartTime().toLocalDate();
    }

    private static int precisionOrder(TimelineItemDTO node) {
        return date(node) ? 0 : 1;
    }

    private static LocalDateTime exactStart(TimelineItemDTO node) {
        return exact(node) ? node.getStartTime() : null;
    }

    private static int displayOrder(TimelineItemDTO node) {
        return node.getSortOrder() == null ? 0 : node.getSortOrder();
    }

    private static String nodeKey(TimelineItemDTO node) {
        return node.getNodeKey() == null ? "" : node.getNodeKey();
    }
}
