package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** A single evidence repair, with frozen facts and complete-array revalidation. */
final class DocumentImportTimelineRecovery {
    private final ObjectMapper mapper;
    private final DocumentImportSuggestionValidator validator;

    DocumentImportTimelineRecovery(ObjectMapper mapper, DocumentImportSuggestionValidator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    Outcome recover(String raw, DocumentImportTarget target, String source, String importId,
                    DocumentImportSuggestionValidator.ValidationResult initial,
                    Function<JsonNode, String> repair, BooleanSupplier stopped) {
        if (initial.suggestions().stream().anyMatch(c -> "/timeline".equals(c.target())))
            return new Outcome(raw, initial, "UNCHANGED", 0, 0);
        if (source == null || source.isBlank() || source.length() > 50000)
            return new Outcome(raw, initial, "SOURCE_LIMIT", 0, 0);
        ObjectNode root;
        try { root = (ObjectNode) mapper.readTree(raw); }
        catch (Exception exception) { return new Outcome(raw, initial, "INVALID_ENVELOPE", 0, 0); }
        ArrayNode candidates = (ArrayNode) root.path("suggestions");
        int slot = -1;
        for (int i = 0; i < candidates.size(); i++) if ("/timeline".equals(candidates.get(i).path("target").asText())) {
            if (slot >= 0) return new Outcome(raw, initial, "AMBIGUOUS_DUPLICATE", 0, 0);
            slot = i;
        }
        if (slot < 0 || !candidates.get(slot).path("value").isArray())
            return new Outcome(raw, initial, "NO_RECOVERABLE_ARRAY", 0, 0);
        ObjectNode frozen = candidates.get(slot).deepCopy();
        if (frozen.path("value").isEmpty() || frozen.path("value").size() > 100)
            return new Outcome(raw, initial, "ARRAY_LIMIT", 0, 0);
        boolean typeCorrected = false;
        // Only the observed promotion/preparation mistake is corrected. No OTHER -> START conversion.
        for (JsonNode node : frozen.path("value")) {
            String type = node.path("type").asText();
            String title = node.path("title").asText();
            if (((target == DocumentImportTarget.ACTIVITY && type.equals("ACTIVITY_START"))
                    || (target == DocumentImportTarget.PUBLIC_EVENT && type.equals("PUBLIC_EVENT_START")))
                    && title.equals("启动阶段") && source.contains("启动阶段")
                    && java.util.regex.Pattern.compile("启动阶段[^。；]{0,350}(?:宣传|动员|赛事准备)").matcher(source).find()
                    && node.path("description").asText().matches("(?s).*(宣传|动员|赛事准备).*")) {
                ((ObjectNode) node).put("type", "OTHER");
                typeCorrected = true;
            }
        }
        candidates.set(slot, frozen);
        var local = validator.validate(root.toString(), target, source, importId);
        if (typeCorrected && adoptable(initial, local, source))
            return accepted(root.toString(), initial, local, "PREPARATION_TYPE_CORRECTED", 0, 0);
        // Structural and target errors are not evidence errors and must not trigger a paid repair.
        var failures = new ArrayList<DocumentImportSuggestionValidator.Rejection>();
        validator.validate(root.toString(), target, source, importId, failures::add);
        if (failures.stream().filter(r -> "/timeline".equals(r.target()))
                .noneMatch(r -> "source-evidence".equals(r.stage())))
            return new Outcome(raw, initial, "NOT_EVIDENCE_FAILURE", 0, 0);
        if (stopped.getAsBoolean()) return new Outcome(raw, initial, "CANCELLED_OR_BUDGET", 0, 0);
        long started = System.nanoTime();
        try {
            String repaired = repair.apply(frozen.path("value").deepCopy());
            if (stopped.getAsBoolean()) return new Outcome(raw, initial, "CANCELLED", 1, elapsed(started));
            // Parse and validate the new envelope strictly before selecting its evidence.
            var proposedValidation = validator.validate(repaired, target, source, importId);
            if (proposedValidation.suggestions().size() != 1
                    || !"/timeline".equals(proposedValidation.suggestions().get(0).target()))
                return new Outcome(raw, initial, "REPAIR_INVALID", 1, elapsed(started));
            JsonNode response = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(repaired);
            if (response.path("suggestions").size() != 1) return new Outcome(raw, initial, "REPAIR_SCOPE_CHANGED", 1, elapsed(started));
            JsonNode proposed = response.path("suggestions").get(0);
            if (!"/timeline".equals(proposed.path("target").asText()) || !frozen.path("value").equals(proposed.path("value")))
                return new Outcome(raw, initial, "FROZEN_VALUE_CHANGED", 1, elapsed(started));
            frozen.set("excerpts", proposed.path("excerpts").deepCopy());
            var checked = validator.validate(root.toString(), target, source, importId);
            if (!adoptable(initial, checked, source)) return new Outcome(raw, initial, "REVALIDATION_FAILED", 1, elapsed(started));
            return accepted(root.toString(), initial, checked, typeCorrected
                    ? "EVIDENCE_AND_PREPARATION_TYPE_REPAIRED" : "EVIDENCE_REPAIRED", 1, elapsed(started));
        } catch (RuntimeException | java.io.IOException exception) {
            // Optional read-only generation failure never discards already validated candidates.
            return new Outcome(raw, initial, "REPAIR_FAILED", 1, elapsed(started));
        }
    }

    private boolean adoptable(DocumentImportSuggestionValidator.ValidationResult initial,
                              DocumentImportSuggestionValidator.ValidationResult checked, String source) {
        var timeline = checked.suggestions().stream().filter(c -> "/timeline".equals(c.target())).findFirst();
        if (timeline.isEmpty() || !checked.suggestions().containsAll(initial.suggestions())) return false;
        var matcher = new DocumentImportEvidenceMatcher(source);
        var evidence = timeline.get().excerpts().stream().map(matcher::match).toList();
        for (JsonNode node : timeline.get().value()) if (node.has("location")) {
            var location = matcher.match(node.path("location").asText());
            if (location == null) return false;
            for (int i = location.start(); i < location.end(); i++) {
                if (Character.isWhitespace(source.charAt(i))) continue;
                boolean supported = false;
                for (var citation : evidence) if (citation != null && citation.start() <= i && citation.end() > i) {
                    supported = true;
                    break;
                }
                if (!supported) return false;
            }
        }
        return true;
    }

    private Outcome accepted(String raw, DocumentImportSuggestionValidator.ValidationResult initial,
                             DocumentImportSuggestionValidator.ValidationResult checked, String mode, int calls, long millis) {
        var warnings = new ArrayList<DocumentImportSuggestionValidator.Warning>();
        initial.warnings().stream().filter(w -> !"/timeline".equals(w.target())).forEach(warnings::add);
        checked.warnings().forEach(w -> { if (!warnings.contains(w)) warnings.add(w); });
        warnings.add(new DocumentImportSuggestionValidator.Warning("INVALID_SUGGESTION", "/timeline",
                "时间线已整体重新校验；恢复未增删节点或修改日期、钟点及业务内容。"
                        + (mode.contains("PREPARATION") ? "原文宣传准备的启动阶段已按 OTHER 分类。" : "来源引用已重新定位。")));
        return new Outcome(raw, new DocumentImportSuggestionValidator.ValidationResult(checked.suggestions(), List.copyOf(warnings)), mode, calls, millis);
    }

    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    record Outcome(String raw, DocumentImportSuggestionValidator.ValidationResult result, String mode, int modelCalls, long elapsedMillis) { }
}
