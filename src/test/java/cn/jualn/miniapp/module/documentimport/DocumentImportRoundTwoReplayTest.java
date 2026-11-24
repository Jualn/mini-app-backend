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
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "documentImport.samples", matches = ".+")
class DocumentImportRoundTwoReplayTest {
    @Test void replayHistoricalAndCompletedOnlineBodiesWithoutModelCalls() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var validator = new DocumentImportSuggestionValidator(mapper);
        var recovery = new DocumentImportSectionsRecovery(mapper, validator);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> directories = new ArrayList<>(List.of(System.getProperty("documentImport.samples"),
                System.getProperty("documentImport.liveSamples", "target/document-import-live-samples")));
        if (System.getProperty("documentImport.round2LiveSamples") != null) directories.add(System.getProperty("documentImport.round2LiveSamples"));
        if (System.getProperty("documentImport.timeLiveSamples") != null) directories.add(System.getProperty("documentImport.timeLiveSamples"));
        for (String directory : directories) {
            try (var files = Files.list(Path.of(directory))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    JsonNode sample = mapper.readTree(file.toFile());
                    if (!"validated".equals(sample.path("outcome").asText())) continue;
                    String source = sample.path("extractedText").asText();
                    String raw = sample.path("rawModelOutput").asText();
                    String id = sample.path("importId").asText();
                    var rejections = new ArrayList<DocumentImportSuggestionValidator.Rejection>();
                    var initial = validator.validate(raw, DocumentImportTarget.ACTIVITY, source, id, rejections::add);
                    long started = System.nanoTime();
                    var out = recovery.recover(raw, DocumentImportTarget.ACTIVITY, source, id, initial);
                    long recoveryMicros = (System.nanoTime() - started) / 1000;
                    assertThat(out.result().suggestions()).containsAll(initial.suggestions());
                    out.result().suggestions().forEach(c -> c.excerpts().forEach(e -> assertThat(source).contains(e)));
                    JsonNode rawSections = null;
                    JsonNode rawTimeline = null;
                    JsonNode malformedSections = null;
                    for (JsonNode c : mapper.readTree(raw).path("suggestions")) {
                        if ("/sections".equals(c.path("target").asText())) rawSections = c;
                        if ("sections".equals(c.path("target").asText())) malformedSections = c;
                        if ("/timeline".equals(c.path("target").asText())) rawTimeline = c;
                    }
                    List<Map<String, Object>> failing = new ArrayList<>();
                    boolean bodyStructureValid = false;
                    if (rawSections != null) {
                        var matcher = new DocumentImportEvidenceMatcher(source);
                        int index = 0;
                        for (JsonNode e : rawSections.path("excerpts")) {
                            if (matcher.match(e.asText()) == null) failing.add(Map.of("excerptIndex", index, "excerpt", e.asText()));
                            index++;
                        }
                        // Diagnostic probe only: tests array structure independently, never emits this candidate.
                        var probe = rawSections.deepCopy();
                        ((com.fasterxml.jackson.databind.node.ObjectNode) probe).set("excerpts", mapper.valueToTree(List.of(source)));
                        var probeResult = validator.validate(mapper.writeValueAsString(Map.of("suggestions", List.of(probe))),
                                DocumentImportTarget.ACTIVITY, source, id);
                        bodyStructureValid = !probeResult.suggestions().isEmpty();
                    }
                    var row = new LinkedHashMap<String, Object>();
                    row.put("importId", id);
                    row.put("sourceDirectory", directory);
                    row.put("rawCandidates", mapper.readTree(raw).path("suggestions").size());
                    row.put("rawSections", rawSections != null);
                    row.put("rawSectionsGenerated", rawSections != null || malformedSections != null);
                    row.put("malformedSectionsTarget", malformedSections != null);
                    row.put("rawSectionCount", rawSections != null ? rawSections.path("value").size()
                            : malformedSections != null ? malformedSections.path("value").size() : 0);
                    row.put("initialSectionCount", count(initial, "/sections"));
                    row.put("finalSectionCount", count(out.result(), "/sections"));
                    row.put("rawTimelineCount", rawTimeline == null ? 0 : rawTimeline.path("value").size());
                    row.put("initialTimelineCount", count(initial, "/timeline"));
                    row.put("finalTimelineCount", count(out.result(), "/timeline"));
                    row.put("sectionClassification", malformedSections != null ? "GENERATED_REJECTED_MALFORMED_TARGET"
                            : rawSections == null ? "NOT_GENERATED" : count(initial, "/sections") == 0
                            ? "GENERATED_REJECTED" : "ACCEPTED_COMPLETENESS_REQUIRES_MANUAL_REVIEW");
                    row.put("initialAccepted", initial.suggestions().size());
                    row.put("finalAccepted", out.result().suggestions().size());
                    row.put("initialRejected", rejections);
                    List<Map<String, Object>> allFailing = new ArrayList<>();
                    var matcher = new DocumentImportEvidenceMatcher(source);
                    for (JsonNode candidate : mapper.readTree(raw).path("suggestions")) {
                        int index = 0;
                        for (JsonNode excerpt : candidate.path("excerpts")) {
                            if (matcher.match(excerpt.asText()) == null) allFailing.add(Map.of("target", candidate.path("target").asText(),
                                    "excerptIndex", index, "excerpt", excerpt.asText()));
                            index++;
                        }
                    }
                    row.put("allFailingExcerpts", allFailing);
                    row.put("failingSectionExcerpts", failing);
                    row.put("bodyStructureValidDiagnosticOnly", bodyStructureValid);
                    row.put("sectionsRecovery", out.mode());
                    row.put("recoveryMicros", recoveryMicros);
                    row.put("recoveryModelCalls", 0);
                    if ("SOURCE_TEXT_FALLBACK".equals(out.mode())) {
                        var section = out.result().suggestions().stream().filter(c -> c.target().equals("/sections")).findFirst().orElseThrow();
                        assertThat(section.value().at("/0/content").asText()).isEqualTo(source);
                        row.put("fallbackExactExtractedTextRetention", true);
                    }
                    rows.add(row);
                }
            }
        }
        if (directories.size() == 2) assertThat(rows).hasSize(13); else assertThat(rows.size()).isGreaterThanOrEqualTo(13);
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/document-import-round2-replay.json").toFile(), rows);
    }

    private int count(DocumentImportSuggestionValidator.ValidationResult result, String target) {
        return result.suggestions().stream().filter(c -> c.target().equals(target)).mapToInt(c -> c.value().size()).sum();
    }
}
