package cn.jualn.miniapp.module.points.bo;

import cn.jualn.miniapp.module.points.vo.PointsCounterSerializer;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.time.LocalDateTime;
import java.util.List;

import static cn.jualn.miniapp.module.points.PointsProblems.invalid;

public final class PointAcceptanceSettings {
    private PointAcceptanceSettings() {}
    public record Decision(Boolean enabled, String reason) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Decision parse(JsonNode root) {
            if (root == null || !root.isObject()) throw invalid("body");
            root.fieldNames().forEachRemaining(field -> {
                if (!"enabled".equals(field) && !"reason".equals(field)) throw invalid(field);
            });
            if (!root.has("enabled") || !root.get("enabled").isBoolean()) throw invalid("enabled");
            if (!root.has("reason") || !root.get("reason").isTextual()) throw invalid("reason");
            return new Decision(root.get("enabled").booleanValue(), root.get("reason").textValue());
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Setting(String organizationId, String organizationName, String organizationStatus,
                          String state, String version, Boolean enabled, String evidenceId, String reason,
                          String recordedBy, String recordedAt) {}
    public record Page(List<Setting> items, int page, int pageSize,
                       @JsonSerialize(using = PointsCounterSerializer.class) long totalItems, String evaluatedAt) {}
    public record Row(String organizationId, String organizationName, String organizationStatus,
                      String evidenceId, String evidenceOrganizationId, String verification, Boolean enabled,
                      String reason, Long recordedBy, LocalDateTime recordedAt) {}
    public record Receipt(String id, long actor, String organizationId, String commandKey,
                          String expectedVersion, boolean enabled, String reason, String resultJson,
                          String evidenceId, LocalDateTime recordedAt) {}
}
