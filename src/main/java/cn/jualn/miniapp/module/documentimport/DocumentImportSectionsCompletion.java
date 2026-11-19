package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append only demonstrably unaccounted-for source blocks; never invent or rewrite passed prose. */
final class DocumentImportSectionsCompletion {
    private final ObjectMapper mapper;
    private final DocumentImportSuggestionValidator validator;
    DocumentImportSectionsCompletion(ObjectMapper mapper, DocumentImportSuggestionValidator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    Outcome complete(String raw, DocumentImportTarget target, String source, String id,
                     DocumentImportSuggestionValidator.ValidationResult initial, String recoveryMode) {
        var coverage = new DocumentImportContentCoverage().inspect(source, initial, recoveryMode);
        if (!"ACCEPTED_COVERAGE_REQUIRES_REVIEW".equals(coverage.state())) return new Outcome(initial, "UNCHANGED", 0);
        var original = initial.suggestions().stream().filter(c -> "/sections".equals(c.target())).findFirst().orElseThrow();
        Map<String, List<DocumentImportContentCoverage.Block>> missing = new LinkedHashMap<>();
        for (var block : coverage.blocks()) if ("COVERAGE_NOT_ESTABLISHED".equals(block.status()))
            missing.computeIfAbsent(block.chapter(), ignored -> new ArrayList<>()).add(block);
        if (original.value().size() + missing.size() > 50) return new Outcome(initial, "SECTION_LIMIT", 0);
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(raw);
            ArrayNode suggestions = (ArrayNode) root.path("suggestions");
            int slot = -1;
            for (int i = 0; i < suggestions.size(); i++) if ("/sections".equals(suggestions.get(i).path("target").asText())) {
                if (slot >= 0) return new Outcome(initial, "AMBIGUOUS_DUPLICATE", 0);
                slot = i;
            }
            ObjectNode replacement = mapper.createObjectNode().put("target", "/sections");
            ArrayNode sections = replacement.putArray("value");
            original.value().forEach(section -> {
                ObjectNode copy = sections.addObject();
                for (String field : List.of("title", "content", "format")) copy.set(field, section.get(field).deepCopy());
            });
            ArrayNode excerpts = replacement.putArray("excerpts");
            original.excerpts().forEach(excerpts::add);
            int added = 0;
            for (var chapter : missing.entrySet()) {
                StringBuilder content = new StringBuilder();
                for (var block : chapter.getValue()) {
                    String literal = source.substring(block.sourceStart(), block.sourceEnd()).strip();
                    if (!content.isEmpty()) content.append('\n');
                    content.append(literal);
                    for (int start = 0; start < literal.length();) {
                        int end = Math.min(start + 4000, literal.length());
                        if (end < literal.length() && Character.isHighSurrogate(literal.charAt(end - 1))) end--;
                        excerpts.add(literal.substring(start, end));
                        start = end;
                    }
                    added++;
                }
                sections.addObject().put("title", chapter.getKey() + "（原文补充）")
                        .put("content", content.toString()).put("format", "PLAIN_TEXT");
            }
            if (excerpts.size() > 100) return new Outcome(initial, "EVIDENCE_LIMIT", 0);
            if (slot < 0) suggestions.add(replacement); else suggestions.set(slot, replacement);
            var checked = validator.validate(root.toString(), target, source, id);
            var body = checked.suggestions().stream().filter(c -> "/sections".equals(c.target())).findFirst();
            if (body.isEmpty()) return new Outcome(initial, "REVALIDATION_FAILED", 0);
            for (int i = 0; i < original.value().size(); i++)
                if (!original.value().get(i).equals(body.get().value().get(i))) return new Outcome(initial, "EXISTING_BODY_CHANGED", 0);
            if (initial.suggestions().stream().filter(c -> !"/sections".equals(c.target()))
                    .anyMatch(c -> !checked.suggestions().contains(c))) return new Outcome(initial, "OTHER_FIELD_CHANGED", 0);
            var warnings = new ArrayList<>(initial.warnings());
            warnings.add(new DocumentImportSuggestionValidator.Warning("INCOMPLETE_CONFIGURATION", "/sections",
                    "已按原文章节补入缺少覆盖依据的信息块，标为“原文补充”；保留已通过正文，未推断新事实，不代表改写内容已完成语义完整性验证。"));
            return new Outcome(new DocumentImportSuggestionValidator.ValidationResult(checked.suggestions(), List.copyOf(warnings)),
                    "MISSING_SOURCE_BLOCKS_APPENDED", added);
        } catch (java.io.IOException exception) {
            return new Outcome(initial, "INVALID_ENVELOPE", 0);
        }
    }
    record Outcome(DocumentImportSuggestionValidator.ValidationResult result, String mode, int appendedBlocks) { }
}
