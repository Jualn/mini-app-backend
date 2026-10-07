package cn.jualn.miniapp.module.timeline.service;

import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Compares only confirmed exact actionable semantics; ambiguous add/remove/precision changes stay silent. */
public final class ActionableTimelineChangeDetector {
    private ActionableTimelineChangeDetector() { }

    public record Change(String label, String before, String after) { }

    public static List<Change> timeChanges(List<TimelineItemDTO> before, List<TimelineItemDTO> after, Set<String> semantics) {
        java.util.ArrayList<Change> result = new java.util.ArrayList<>();
        for (String semantic : semantics.stream().sorted().toList()) {
            TimelineItemDTO oldNode = uniqueExact(before, semantic);
            TimelineItemDTO newNode = uniqueExact(after, semantic);
            if (oldNode != null && newNode != null && !Objects.equals(oldNode.getStartTime(), newNode.getStartTime())) {
                String label = newNode.getLabel() == null || newNode.getLabel().isBlank() ? semantic : newNode.getLabel();
                result.add(new Change(label, oldNode.getStartTime().atOffset(java.time.ZoneOffset.ofHours(8)).toString(),
                        newNode.getStartTime().atOffset(java.time.ZoneOffset.ofHours(8)).toString()));
            }
        }
        return List.copyOf(result);
    }

    public static List<Change> locationChanges(List<TimelineItemDTO> before, List<TimelineItemDTO> after, Set<String> semantics) {
        java.util.ArrayList<Change> result = new java.util.ArrayList<>();
        for (String semantic : semantics.stream().sorted().toList()) {
            TimelineItemDTO oldNode = unique(before, semantic);
            TimelineItemDTO newNode = unique(after, semantic);
            if (oldNode != null && newNode != null && effectiveValueChanged(oldNode.getLocation(), newNode.getLocation())) {
                String label = newNode.getLabel() == null || newNode.getLabel().isBlank() ? semantic : newNode.getLabel();
                result.add(new Change(label + "地点", oldNode.getLocation().trim(), newNode.getLocation().trim()));
            }
        }
        return List.copyOf(result);
    }

    public static boolean exactTimeChanged(List<TimelineItemDTO> before, List<TimelineItemDTO> after,
                                           Set<String> semantics) {
        for (String semantic : semantics) {
            TimelineItemDTO oldNode = uniqueExact(before, semantic);
            TimelineItemDTO newNode = uniqueExact(after, semantic);
            if (oldNode != null && newNode != null
                    && !Objects.equals(oldNode.getStartTime(), newNode.getStartTime())) return true;
        }
        return false;
    }

    public static boolean effectiveLocationChanged(List<TimelineItemDTO> before, List<TimelineItemDTO> after,
                                                   Set<String> semantics) {
        for (String semantic : semantics) {
            TimelineItemDTO oldNode = unique(before, semantic);
            TimelineItemDTO newNode = unique(after, semantic);
            if (oldNode != null && newNode != null && effective(oldNode.getLocation())
                    && effective(newNode.getLocation())
                    && !oldNode.getLocation().trim().equals(newNode.getLocation().trim())) return true;
        }
        return false;
    }

    public static boolean effectiveValueChanged(String before, String after) {
        return effective(before) && effective(after) && !before.trim().equals(after.trim());
    }

    private static TimelineItemDTO uniqueExact(List<TimelineItemDTO> nodes, String semantic) {
        TimelineItemDTO node = unique(nodes, semantic);
        return node != null && Integer.valueOf(2).equals(node.getStartPrecision())
                && node.getStartTime() != null ? node : null;
    }

    private static TimelineItemDTO unique(List<TimelineItemDTO> nodes, String semantic) {
        List<TimelineItemDTO> matches = nodes == null ? List.of() : nodes.stream()
                .filter(node -> semantic.equals(node.getNodeType())).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean effective(String value) {
        return value != null && !value.isBlank();
    }
}
