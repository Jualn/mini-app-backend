package cn.jualn.miniapp.module.timeline.service;

import cn.jualn.miniapp.module.timeline.bo.TimelineFactBO;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Compares only confirmed exact actionable semantics; ambiguous add/remove/precision changes stay silent. */
public final class ActionableTimelineChangeDetector {
    private ActionableTimelineChangeDetector() { }

    public record Change(String label, String before, String after) { }

    /** Activity V1 only; PublicEvent keeps its existing comparison semantics. */
    public static List<Change> activityTimeChanges(List<TimelineFactBO> before, List<TimelineFactBO> after) {
        java.util.ArrayList<Change> result = new java.util.ArrayList<>();
        for (String semantic : List.of("REGISTRATION_START", "REGISTRATION_END", "ACTIVITY_START")) {
            List<TimelineFactBO> oldNodes = matches(before, semantic);
            List<TimelineFactBO> newNodes = matches(after, semantic);
            if (oldNodes.size() > 1 || newNodes.size() > 1) continue;
            TimelineFactBO oldNode = oldNodes.isEmpty() ? null : oldNodes.get(0);
            TimelineFactBO newNode = newNodes.isEmpty() ? null : newNodes.get(0);
            if (!confirmedState(oldNode, semantic) || !confirmedState(newNode, semantic)) continue;
            boolean main = "ACTIVITY_START".equals(semantic);
            String oldTime = scheduleValue(oldNode, main);
            String newTime = scheduleValue(newNode, main);
            boolean bothRanges = oldNode != null && newNode != null && oldNode.getEndTime() != null && newNode.getEndTime() != null;
            boolean sameStart = java.util.Objects.equals(anchorValue(oldNode), anchorValue(newNode));
            if (sameStart && !bothRanges || java.util.Objects.equals(oldTime, newTime)) continue;
            String label = switch (semantic) {
                case "REGISTRATION_START" -> "报名开始";
                case "REGISTRATION_END" -> "报名截止";
                default -> "活动开始";
            };
            result.add(new Change(label, oldTime == null ? "原待定" : oldTime,
                    newTime == null ? (newNode == null ? "已撤回" : "现待定")
                            : newTime));
        }
        return List.copyOf(result);
    }

    private static String anchorValue(TimelineFactBO node) {
        if (node == null || node.getStartTime() == null) return null;
        return Integer.valueOf(1).equals(node.getStartPrecision()) ? node.getStartTime().toLocalDate().toString()
                : node.getStartTime().atOffset(java.time.ZoneOffset.ofHours(8)).toString();
    }
    private static String scheduleValue(TimelineFactBO node, boolean main) {
        String start = anchorValue(node);
        if (start == null || !main || node.getEndTime() == null) return start;
        return start + " / " + (Integer.valueOf(1).equals(node.getEndPrecision()) ? node.getEndTime().toLocalDate()
                : node.getEndTime().atOffset(java.time.ZoneOffset.ofHours(8)));
    }
    public static boolean activityLocationChanged(String before, String after) {
        return !java.util.Objects.equals(locationValue(before), locationValue(after));
    }
    public static String locationValue(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private static List<TimelineFactBO> matches(List<TimelineFactBO> nodes, String semantic) {
        return nodes == null ? List.of() : nodes.stream().filter(n -> semantic.equals(n.getNodeType())).toList();
    }

    private static java.time.LocalDateTime anchor(TimelineFactBO node) {
        return node != null && Integer.valueOf(2).equals(node.getStartPrecision()) ? node.getStartTime() : null;
    }

    private static boolean confirmedState(TimelineFactBO node, String semantic) {
        if (node == null) return true;
        if (Integer.valueOf(0).equals(node.getStartPrecision()))
            return node.getStartTime() == null && node.getEndTime() == null;
        if (!(Integer.valueOf(2).equals(node.getStartPrecision()) || "ACTIVITY_START".equals(semantic) && Integer.valueOf(1).equals(node.getStartPrecision())) || node.getStartTime() == null) return false;
        if (node.getEndTime() == null) return node.getEndPrecision() == null || Integer.valueOf(0).equals(node.getEndPrecision());
        return "ACTIVITY_START".equals(semantic) && node.getStartPrecision().equals(node.getEndPrecision())
                && !node.getEndTime().isBefore(node.getStartTime());
    }

    public static List<Change> timeChanges(List<TimelineFactBO> before, List<TimelineFactBO> after, Set<String> semantics) {
        java.util.ArrayList<Change> result = new java.util.ArrayList<>();
        for (String semantic : semantics.stream().sorted().toList()) {
            TimelineFactBO oldNode = uniqueExact(before, semantic);
            TimelineFactBO newNode = uniqueExact(after, semantic);
            if (oldNode != null && newNode != null && !Objects.equals(oldNode.getStartTime(), newNode.getStartTime())) {
                String label = newNode.getLabel() == null || newNode.getLabel().isBlank() ? semantic : newNode.getLabel();
                result.add(new Change(label, oldNode.getStartTime().atOffset(java.time.ZoneOffset.ofHours(8)).toString(),
                        newNode.getStartTime().atOffset(java.time.ZoneOffset.ofHours(8)).toString()));
            }
        }
        return List.copyOf(result);
    }

    public static List<Change> locationChanges(List<TimelineFactBO> before, List<TimelineFactBO> after, Set<String> semantics) {
        java.util.ArrayList<Change> result = new java.util.ArrayList<>();
        for (String semantic : semantics.stream().sorted().toList()) {
            TimelineFactBO oldNode = unique(before, semantic);
            TimelineFactBO newNode = unique(after, semantic);
            if (oldNode != null && newNode != null && effectiveValueChanged(oldNode.getLocation(), newNode.getLocation())) {
                String label = newNode.getLabel() == null || newNode.getLabel().isBlank() ? semantic : newNode.getLabel();
                result.add(new Change(label + "地点", oldNode.getLocation().trim(), newNode.getLocation().trim()));
            }
        }
        return List.copyOf(result);
    }

    public static boolean exactTimeChanged(List<TimelineFactBO> before, List<TimelineFactBO> after,
                                           Set<String> semantics) {
        for (String semantic : semantics) {
            TimelineFactBO oldNode = uniqueExact(before, semantic);
            TimelineFactBO newNode = uniqueExact(after, semantic);
            if (oldNode != null && newNode != null
                    && !Objects.equals(oldNode.getStartTime(), newNode.getStartTime())) return true;
        }
        return false;
    }

    public static boolean effectiveLocationChanged(List<TimelineFactBO> before, List<TimelineFactBO> after,
                                                   Set<String> semantics) {
        for (String semantic : semantics) {
            TimelineFactBO oldNode = unique(before, semantic);
            TimelineFactBO newNode = unique(after, semantic);
            if (oldNode != null && newNode != null && effective(oldNode.getLocation())
                    && effective(newNode.getLocation())
                    && !oldNode.getLocation().trim().equals(newNode.getLocation().trim())) return true;
        }
        return false;
    }

    public static boolean effectiveValueChanged(String before, String after) {
        return effective(before) && effective(after) && !before.trim().equals(after.trim());
    }

    private static TimelineFactBO uniqueExact(List<TimelineFactBO> nodes, String semantic) {
        TimelineFactBO node = unique(nodes, semantic);
        return node != null && Integer.valueOf(2).equals(node.getStartPrecision())
                && node.getStartTime() != null ? node : null;
    }

    private static TimelineFactBO unique(List<TimelineFactBO> nodes, String semantic) {
        List<TimelineFactBO> matches = nodes == null ? List.of() : nodes.stream()
                .filter(node -> semantic.equals(node.getNodeType())).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean effective(String value) {
        return value != null && !value.isBlank();
    }
}
