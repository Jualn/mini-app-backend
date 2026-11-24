package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.module.documentimport.DocumentImportSuggestionValidator.ValidationResult;
import cn.jualn.miniapp.module.documentimport.DocumentImportSuggestionValidator.Warning;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Local recovery only. Never infers facts or accepts a partially valid array. */
final class DocumentImportSectionsRecovery {
    private final ObjectMapper mapper;
    private final DocumentImportSuggestionValidator validator;

    DocumentImportSectionsRecovery(ObjectMapper mapper, DocumentImportSuggestionValidator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    Outcome recover(String raw, DocumentImportTarget target, String source, String importId, ValidationResult initial) {
        if (initial.suggestions().stream().anyMatch(candidate -> "/sections".equals(candidate.target()))) {
            return new Outcome(initial, "UNCHANGED");
        }
        if (source == null || source.isBlank() || source.length() > 50000) {
            return new Outcome(initial, "SOURCE_LIMIT");
        }
        ObjectNode root;
        try {
            // The caller must first run the strict validator on the original envelope.
            root = (ObjectNode) mapper.readTree(raw);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Recovery requires a validated model envelope", exception);
        }
        ArrayNode items = (ArrayNode) root.path("suggestions");
        int slot = -1;
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            if ("/sections".equals(items.get(i).path("target").asText())) {
                slot = i;
                count++;
            }
        }
        // Duplicate candidates have no unique value to freeze. Preserve that rejection.
        if (count > 1) return new Outcome(initial, "AMBIGUOUS_DUPLICATE");
        ObjectNode replacement = slot < 0 ? mapper.createObjectNode() : items.get(slot).deepCopy();
        if (slot >= 0) {
            ArrayNode restored = pageFooterCitations(replacement.path("excerpts"), source);
            if (restored != null) {
                ObjectNode correctedRoot = root.deepCopy();
                ((ObjectNode) correctedRoot.path("suggestions").get(slot)).set("excerpts", restored);
                ValidationResult checked = validator.validate(correctedRoot.toString(), target, source, importId);
                if (checked.suggestions().stream().anyMatch(c -> "/sections".equals(c.target()))
                        && checked.suggestions().containsAll(initial.suggestions())) {
                    var warnings = new ArrayList<>(initial.warnings());
                    warnings.removeIf(w -> "/sections".equals(w.target()) && "INVALID_SUGGESTION".equals(w.code()));
                    checked.warnings().forEach(w -> { if (!warnings.contains(w)) warnings.add(w); });
                    warnings.add(new Warning("INVALID_SUGGESTION", "/sections",
                            "已保留模型整理正文，仅还原跨页引用中的原文页脚并完整重新校验，未改写正文或删除原文字符。"));
                    return new Outcome(new ValidationResult(checked.suggestions(), List.copyOf(warnings)), "PAGE_FOOTER_EVIDENCE_RESTORED");
                }
            }
        }
        ArrayNode citations = verbatimCitations(replacement.path("value"), source);
        String mode = "EXCERPTS_RELOCATED";
        if (citations == null) {
            mode = "SOURCE_TEXT_FALLBACK";
            replacement = mapper.createObjectNode();
            replacement.put("target", "/sections");
            replacement.putArray("value").addObject()
                    .put("title", "文档提取原文（需人工核对）")
                    .put("content", source).put("format", "PLAIN_TEXT");
            citations = mapper.createArrayNode();
            for (int start = 0; start < source.length();) {
                int end = Math.min(start + 4000, source.length());
                if (end < source.length() && Character.isHighSurrogate(source.charAt(end - 1))) end--;
                String excerpt = source.substring(start, end);
                if (!excerpt.isBlank()) citations.add(excerpt);
                start = end;
            }
        }
        replacement.set("excerpts", citations);
        if (slot < 0) items.add(replacement); else items.set(slot, replacement);
        ValidationResult checked = validator.validate(root.toString(), target, source, importId);
        if (checked.suggestions().stream().noneMatch(candidate -> "/sections".equals(candidate.target()))
                || initial.suggestions().stream().anyMatch(candidate -> !checked.suggestions().contains(candidate))) {
            return new Outcome(initial, "REVALIDATION_FAILED");
        }
        List<Warning> warnings = new ArrayList<>(initial.warnings());
        for (Warning warning : checked.warnings()) if (!warnings.contains(warning)) warnings.add(warning);
        warnings.add(new Warning("INVALID_SUGGESTION", "/sections", "EXCERPTS_RELOCATED".equals(mode)
                ? "正文内容保持原候选，仅按可定位的完整标题及内容重建来源并重新校验，请核对。"
                : "这是提取原文参考，不代表 AI 已完成正文整理。原文可能包含页码、落款、附件说明、联系人及电话；保存前需人工核对公开范围并整理正文，图片及附件内容仍可能缺失。"));
        return new Outcome(new ValidationResult(checked.suggestions(), List.copyOf(warnings)), mode);
    }

    private ArrayNode pageFooterCitations(JsonNode excerpts, String source) {
        if (!excerpts.isArray() || excerpts.isEmpty() || excerpts.size() > 100) return null;
        var matcher = new DocumentImportEvidenceMatcher(source);
        ArrayNode restored = mapper.createArrayNode();
        boolean changed = false;
        for (JsonNode excerpt : excerpts) {
            if (!excerpt.isTextual() || excerpt.asText().isBlank() || excerpt.asText().length() > 4000) return null;
            var original = matcher.match(excerpt.asText());
            if (original != null) { restored.add(original.original()); continue; }
            int first = -1;
            int last = -1;
            boolean footerFound = false;
            for (String line : excerpt.asText().split("\\r?\\n")) {
                if (line.isBlank()) continue;
                var part = matcher.match(line.strip());
                if (part == null || part.start() < last) return null;
                if (first < 0) first = part.start();
                if (last >= 0) {
                    String gap = source.substring(last, part.start());
                    if (gap.matches("[ \\t\\r\\n]*[—–-][ \\t]*[1-9][0-9]{0,2}[ \\t]*[—–-][ \\t\\r\\n]*")
                            && gap.contains("\n")) footerFound = true;
                    else if (!gap.matches("[ \\t\\r]*(?:\\n[ \\t\\r]*)?")) return null;
                }
                last = part.end();
            }
            if (!footerFound || first < 0 || last - first > 4000) return null;
            // Return the exact continuous source, including the footer. Never erase a number to get a match.
            restored.add(source.substring(first, last));
            changed = true;
        }
        return changed ? restored : null;
    }

    private ArrayNode verbatimCitations(JsonNode sections, String source) {
        if (!sections.isArray() || sections.isEmpty() || sections.size() > 50) return null;
        var matcher = new DocumentImportEvidenceMatcher(source);
        ArrayNode citations = mapper.createArrayNode();
        for (JsonNode section : sections) {
            if (!"PLAIN_TEXT".equals(section.path("format").asText())) return null;
            DocumentImportEvidenceMatcher.Match title = null;
            for (String field : List.of("title", "content")) {
                JsonNode text = section.path(field);
                if (!text.isTextual() || text.asText().isBlank() || text.asText().length() > 4000) return null;
                var match = matcher.match(text.asText());
                if (match == null) return null;
                if (field.equals("title")) title = match;
                else if (match.start() < title.end()
                        || !source.substring(title.end(), match.start()).matches("[ \\t\\r\\n]*")) return null;
                citations.add(match.original());
            }
        }
        return citations;
    }

    record Outcome(ValidationResult result, String mode) { }
}
