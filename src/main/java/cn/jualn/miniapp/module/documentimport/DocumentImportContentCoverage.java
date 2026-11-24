package cn.jualn.miniapp.module.documentimport;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Conservative source-block diagnostics, never semantic acceptance or automatic completion. */
final class DocumentImportContentCoverage {
    private static final Pattern HEADING = Pattern.compile("(?m)^([一二三四五六七八九十]{1,3})、([^\\n]{1,60})$");
    private static final Pattern IMPORTANT = Pattern.compile("资格|主体|对象|报名|提交|材料|要求|规则|赛程|时间|地点|条件|注意");
    private static final Pattern TIME_LOCATION = Pattern.compile("赛程|时间|地点");
    private static final Pattern CONTACTS = Pattern.compile("(?m)^\\s*(?:联系人及联系方式|联系人|联系方式)[：:]\\s*$");
    private static final Pattern PAGE_FOOTER = Pattern.compile("(?m)^[ \\t]*[—–-][ \\t]*[1-9][0-9]{0,2}[ \\t]*[—–-][ \\t]*\\r?$");

    Assessment inspect(String source, DocumentImportSuggestionValidator.ValidationResult result, String recoveryMode) {
        var body = result.suggestions().stream().filter(c -> "/sections".equals(c.target())).findFirst();
        String mode = "SOURCE_TEXT_FALLBACK".equals(recoveryMode) ? "RAW_REFERENCE"
                : body.isEmpty() ? "NO_ACCEPTED_BODY" : "ACCEPTED_BODY";
        StringBuilder organized = new StringBuilder();
        List<DocumentImportEvidenceMatcher.Match> citations = new ArrayList<>();
        List<DocumentImportEvidenceMatcher.Match> timeLocationCitations = new ArrayList<>();
        var matcher = new DocumentImportEvidenceMatcher(source);
        // These dedicated fields can carry time/place facts without duplicating them in body text.
        // Summary and other fields must not mask omitted qualifications or working requirements.
        for (var candidate : result.suggestions()) if ("/timeline".equals(candidate.target()) || "/primaryLocation".equals(candidate.target())) {
            for (String excerpt : candidate.excerpts()) if (!locateAll(source, matcher, excerpt, timeLocationCitations))
                return new Assessment("SOURCE_LIMIT", mode, List.of());
        }
        if (body.isPresent()) {
            body.get().value().forEach(section -> organized.append(section.path("content").asText()).append('\n'));
            if (organized.length() > 50000) return new Assessment("SOURCE_LIMIT", mode, List.of());
            for (String excerpt : body.get().excerpts()) {
                if (!locateAll(source, matcher, excerpt, citations)) return new Assessment("SOURCE_LIMIT", mode, List.of());
            }
        }
        List<Heading> headings = new ArrayList<>();
        var heading = HEADING.matcher(source);
        while (heading.find()) headings.add(new Heading(heading.group(2).strip(), heading.start(), heading.end()));
        // Bound diagnostic work independently of model verbosity.
        if (headings.size() > 100 || source.length() > 50000) return new Assessment("SOURCE_LIMIT", mode, List.of());
        List<Block> blocks = new ArrayList<>();
        var valueMatcher = new DocumentImportEvidenceMatcher(organized.toString());
        for (int i = 0; i < headings.size(); i++) {
            Heading current = headings.get(i);
            if (!IMPORTANT.matcher(current.title()).find()) continue;
            int end = i + 1 < headings.size() ? headings.get(i + 1).start() : source.length();
            var contacts = CONTACTS.matcher(source);
            if (contacts.find(current.end()) && contacts.start() < end) end = contacts.start();
            int start = current.end();
            int ordinal = 0;
            for (int at = start; at < end; at++) {
                if ("。；".indexOf(source.charAt(at)) < 0 && at + 1 < end) continue;
                int stop = at + 1;
                String statement = source.substring(start, stop).strip();
                int statementStart = start;
                start = stop;
                if (statement.replaceAll("\\s", "").length() < 12) continue;
                String status;
                if (mode.equals("RAW_REFERENCE")) status = "RAW_REFERENCE_ONLY";
                else if (mode.equals("NO_ACCEPTED_BODY")) status = "NO_ACCEPTED_BODY";
                else if (statement.length() <= 4000 && valueMatcher.match(statement) != null) status = "VERBATIM_VALUE";
                else {
                    boolean cited = covers(source, statementStart, stop, citations);
                    boolean otherFieldCited = TIME_LOCATION.matcher(current.title()).find()
                            && covers(source, statementStart, stop, timeLocationCitations);
                    status = cited ? "CITED_REQUIRES_VALUE_REVIEW" : otherFieldCited
                            ? "OTHER_FIELD_EVIDENCE_REQUIRES_REVIEW" : "COVERAGE_NOT_ESTABLISHED";
                }
                blocks.add(new Block(current.title(), ++ordinal, statementStart, stop, status));
                if (blocks.size() >= 200) return new Assessment("SOURCE_LIMIT", mode, List.copyOf(blocks));
            }
        }
        String state = mode.equals("RAW_REFERENCE") ? "RAW_REFERENCE_NOT_AI_ORGANIZATION"
                : mode.equals("NO_ACCEPTED_BODY") ? "NO_ACCEPTED_BODY"
                : blocks.isEmpty() ? "NO_RECOGNIZED_IMPORTANT_BLOCKS"
                : blocks.stream().anyMatch(b -> b.status().equals("COVERAGE_NOT_ESTABLISHED"))
                ? "ACCEPTED_COVERAGE_REQUIRES_REVIEW" : "EVIDENCE_LOCATED_SEMANTIC_REVIEW_REQUIRED";
        return new Assessment(state, mode, List.copyOf(blocks));
    }

    private boolean covers(String source, int start, int end, List<DocumentImportEvidenceMatcher.Match> citations) {
        // A short intersection must not hide the remainder of a business requirement.
        // Only this diagnostic ignores unmistakable standalone page-footer lines; validation never does.
        var footer = PAGE_FOOTER.matcher(source);
        List<int[]> footerSpans = new ArrayList<>();
        while (footer.find()) if (footer.start() >= start && footer.end() <= end)
            footerSpans.add(new int[]{footer.start(), footer.end()});
        for (int i = start; i < end; i++) {
            if (Character.isWhitespace(source.charAt(i))) continue;
            boolean covered = false;
            for (var span : footerSpans) if (span[0] <= i && span[1] > i) { covered = true; break; }
            for (var citation : citations) if (citation.start() <= i && citation.end() > i) { covered = true; break; }
            if (!covered) return false;
        }
        return true;
    }

    private boolean locateAll(String source, DocumentImportEvidenceMatcher matcher, String excerpt,
                              List<DocumentImportEvidenceMatcher.Match> locations) {
        var first = matcher.match(excerpt);
        if (first == null) return true;
        // Repeated evidence, including a separately quoted sentence terminator, can have multiple locations.
        // Keeping only the first location must not cause a duplicate source supplement elsewhere.
        String original = first.original();
        if (original.isEmpty()) return true;
        for (int at = source.indexOf(original); at >= 0; at = source.indexOf(original, at + original.length())) {
            if (locations.size() >= 512) return false;
            locations.add(new DocumentImportEvidenceMatcher.Match(first.mode(), at, at + original.length(), original));
        }
        return true;
    }

    DocumentImportSuggestionValidator.ValidationResult warn(DocumentImportSuggestionValidator.ValidationResult result,
                                                            Assessment assessment) {
        if (!assessment.state().equals("ACCEPTED_COVERAGE_REQUIRES_REVIEW")) return result;
        List<String> chapters = assessment.blocks().stream().filter(b -> b.status().equals("COVERAGE_NOT_ESTABLISHED"))
                .map(Block::chapter).distinct().limit(5).toList();
        var warnings = new ArrayList<>(result.warnings());
        warnings.add(new DocumentImportSuggestionValidator.Warning("INCOMPLETE_CONFIGURATION", "/sections",
                "正文候选已通过合法性校验，但覆盖依据不足：" + String.join("、", chapters)
                        + "中存在未找到完整原文事实句或对应正文引用的信息块，请逐项核对；改写可能已覆盖，本检查不能判定语义完整。"));
        return new DocumentImportSuggestionValidator.ValidationResult(result.suggestions(), List.copyOf(warnings));
    }

    private record Heading(String title, int start, int end) { }
    record Block(String chapter, int ordinal, int sourceStart, int sourceEnd, String status) { }
    record Assessment(String state, String bodyMode, List<Block> blocks) { }
}
