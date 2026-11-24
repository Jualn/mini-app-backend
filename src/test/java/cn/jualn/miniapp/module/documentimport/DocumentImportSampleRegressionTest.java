package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in replay of local private diagnostics. Never calls a model or writes business data. */
@EnabledIfSystemProperty(named = "documentImport.samples", matches = ".+")
class DocumentImportSampleRegressionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @EnabledIfSystemProperty(named = "documentImport.liveSamples", matches = ".+")
    void revalidateCompletedLiveOutputsWithTheCurrentValidator() throws Exception {
        List<Map<String, Object>> report = new ArrayList<>();
        try (var files = Files.list(Path.of(System.getProperty("documentImport.liveSamples")))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                var sample = mapper.readTree(file.toFile());
                if (!"validated".equals(sample.path("outcome").asText())) continue;
                String source = sample.path("extractedText").asText();
                var rejected = new ArrayList<DocumentImportSuggestionValidator.Rejection>();
                var validator = new DocumentImportSuggestionValidator(mapper);
                var initial = validator.validate(sample.path("rawModelOutput").asText(),
                        DocumentImportTarget.ACTIVITY, source, sample.path("importId").asText(), rejected::add);
                var timelineRecovery = new DocumentImportTimelineRecovery(mapper, validator).recover(
                        sample.path("rawModelOutput").asText(), DocumentImportTarget.ACTIVITY, source,
                        sample.path("importId").asText(), initial, ignored -> { throw new AssertionError("offline replay cannot call model"); }, () -> true);
                var recovery = new DocumentImportSectionsRecovery(mapper, validator).recover(
                        timelineRecovery.raw(), DocumentImportTarget.ACTIVITY, source,
                        sample.path("importId").asText(), timelineRecovery.result());
                var completion = new DocumentImportSectionsCompletion(mapper, validator).complete(
                        timelineRecovery.raw(), DocumentImportTarget.ACTIVITY, source, sample.path("importId").asText(),
                        recovery.result(), recovery.mode());
                var result = completion.result();
                result.suggestions().forEach(candidate -> candidate.excerpts().forEach(excerpt -> assertThat(source).contains(excerpt)));
                for (JsonNode previous : sample.at("/validation/suggestions")) {
                    assertThat(result.suggestions()).extracting(DocumentImportSuggestionValidator.Candidate::target)
                            .contains(previous.path("target").asText());
                }
                report.add(Map.of("importId", sample.path("importId").asText(),
                        "rawCandidates", mapper.readTree(sample.path("rawModelOutput").asText()).path("suggestions").size(),
                        "initialAccepted", initial.suggestions().size(), "accepted", result.suggestions().size(),
                        "sectionsRecovery", recovery.mode(), "sectionsCompletion", completion.mode(),
                        "timelineRecovery", timelineRecovery.mode(), "rejected", rejected,
                        "targets", result.suggestions().stream().map(DocumentImportSuggestionValidator.Candidate::target).toList()));
            }
        }
        assertThat(report).isNotEmpty();
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/document-import-live-validation.json").toFile(), report);
    }

    @Test
    void replayNineSamplesAndKeepPreviouslyAcceptedCandidates() throws Exception {
        Path directory = Path.of(System.getProperty("documentImport.samples"));
        List<Map<String, Object>> report = new ArrayList<>();
        for (String prefix : List.of("4c67ce08", "39bcc13c", "69f03c6c", "261b8ec9", "90334004",
                "c7215d6b", "e1c71cd7", "ebd15478", "f0f33d00")) {
            Path file;
            try (var files = Files.list(directory)) {
                file = files.filter(path -> path.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow();
            }
            JsonNode sample = mapper.readTree(file.toFile());
            String source = sample.path("extractedText").asText();
            String raw = sample.path("rawModelOutput").asText();
            var rejected = new ArrayList<DocumentImportSuggestionValidator.Rejection>();
            var matches = new ArrayList<DocumentImportSuggestionValidator.EvidenceMatch>();
            var result = new DocumentImportSuggestionValidator(mapper).validate(raw,
                    DocumentImportTarget.valueOf(sample.path("targetType").asText()), source,
                    sample.path("importId").asText(), rejected::add, matches::add);
            Map<String, DocumentImportSuggestionValidator.Candidate> byTarget = result.suggestions().stream()
                    .collect(Collectors.toMap(DocumentImportSuggestionValidator.Candidate::target, candidate -> candidate));
            for (JsonNode old : sample.at("/validation/suggestions")) {
                assertThat(byTarget).as(prefix).containsKey(old.path("target").asText());
                assertThat(byTarget.get(old.path("target").asText()).value()).isEqualTo(old.path("value"));
            }
            result.suggestions().forEach(candidate -> candidate.excerpts().forEach(excerpt ->
                    assertThat(source).as(prefix + candidate.target()).contains(excerpt)));
            List<String> anchors = source.contains("微团课")
                    ? List.of("8分钟", "3位", "2位", "10月13日16:00", "10月12日12:00", "附件3", "附件4", "参赛PPT")
                    : source.contains("选拔通知")
                    ? List.of("每人最多报两项", "提前15分钟", "自动弃权", "身体状况", "阴雨天气", "5000", "3000", "严禁虚报")
                    : List.of("人才库", "10月10日", "12：00", "专业班级");
            var row = new LinkedHashMap<String, Object>();
            row.put("importIdPrefix", prefix);
            row.put("rawCandidates", mapper.readTree(raw).path("suggestions").size());
            row.put("beforeAccepted", sample.at("/validation/suggestions").size());
            row.put("afterAccepted", result.suggestions().size());
            row.put("rejectedCount", rejected.size());
            row.put("rejected", rejected);
            row.put("beforeSections", hasTarget(sample.at("/validation/suggestions"), "/sections"));
            row.put("afterSections", byTarget.containsKey("/sections"));
            row.put("beforeTimeline", hasTarget(sample.at("/validation/suggestions"), "/timeline"));
            row.put("afterTimeline", byTarget.containsKey("/timeline"));
            row.put("evidenceModes", matches.stream().collect(Collectors.groupingBy(
                    DocumentImportSuggestionValidator.EvidenceMatch::mode, Collectors.counting())));
            row.put("newCandidatesWithoutLocatableEvidence", 0);
            List<String> sourceAnchors = anchors.stream().filter(anchor -> compact(source).contains(anchor)).toList();
            String before = text(sample.at("/validation/suggestions"), "value");
            String after = result.suggestions().stream().map(candidate -> text(candidate.value(), null))
                    .collect(Collectors.joining(" "));
            row.put("factAnchorDenominator", sourceAnchors.size());
            row.put("beforeFactAnchors", sourceAnchors.stream().filter(anchor -> compact(before).contains(anchor)).count());
            row.put("afterFactAnchors", sourceAnchors.stream().filter(anchor -> compact(after).contains(anchor)).count());
            report.add(row);
        }
        Path output = Path.of("target/document-import-regression.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "kind", "deterministic replay; fact anchors are lexical proxies, not semantic coverage or model quality",
                "runs", report));
    }

    private boolean hasTarget(JsonNode candidates, String target) {
        for (JsonNode candidate : candidates) if (target.equals(candidate.path("target").asText())) return true;
        return false;
    }

    private String text(JsonNode node, String onlyField) {
        if (onlyField != null && node.isArray()) {
            StringBuilder output = new StringBuilder();
            node.forEach(candidate -> output.append(text(candidate.path(onlyField), null)).append(' '));
            return output.toString();
        }
        if (node.isTextual()) return node.asText();
        StringBuilder output = new StringBuilder();
        node.elements().forEachRemaining(child -> output.append(text(child, null)).append(' '));
        return output.toString();
    }

    private String compact(String value) { return value.replaceAll("\\s+", ""); }
}
