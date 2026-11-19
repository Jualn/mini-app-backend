package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Node probes are evidence for diagnosis only, never partial-array acceptance. */
@EnabledIfSystemProperty(named = "documentImport.pdfDirectory", matches = ".+")
class DocumentImportThirdRoundAuditTest {
    @Test void auditSecondRoundAndOptionalNewOutputsAgainstOriginalPdf() throws Exception {
        var mapper = new ObjectMapper();
        var validator = new DocumentImportSuggestionValidator(mapper);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String directory : List.of("target/document-import-time-live-samples", "target/document-import-samples",
                System.getProperty("documentImport.thirdRoundSamples", "target/document-import-round3-live-samples"))) {
            if (!Files.isDirectory(Path.of(directory))) continue;
            try (var files = Files.list(Path.of(directory))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                    JsonNode sample = mapper.readTree(file.toFile());
                    if (!sample.path("outcome").asText().equals("validated")) continue;
                    Path pdf = Path.of(System.getProperty("documentImport.pdfDirectory"), sample.path("filename").asText());
                    String extracted = new DocumentImportFileReader().read(new MockMultipartFile("file", pdf.getFileName().toString(),
                            "application/pdf", Files.readAllBytes(pdf))).text();
                    String source = sample.path("extractedText").asText();
                    // Historical diagnostics used default PDF order; compare those with the preserved first-round test.
                    if (!directory.equals("target/document-import-samples")) assertThat(source).isEqualTo(extracted);
                    String raw = sample.path("rawModelOutput").asText();
                    String id = sample.path("importId").asText();
                    var rejected = new ArrayList<DocumentImportSuggestionValidator.Rejection>();
                    var initial = validator.validate(raw, DocumentImportTarget.ACTIVITY, source, id, rejected::add);
                    var recovery = new DocumentImportSectionsRecovery(mapper, validator).recover(raw, DocumentImportTarget.ACTIVITY, source, id, initial);
                    var checker = new DocumentImportContentCoverage();
                    var coverage = checker.inspect(source, recovery.result(), recovery.mode());
                    var finalResult = checker.warn(recovery.result(), coverage);
                    assertThat(finalResult.suggestions()).isEqualTo(recovery.result().suggestions());
                    List<Map<String, Object>> nodes = new ArrayList<>();
                    var matcher = new DocumentImportEvidenceMatcher(source);
                    for (JsonNode c : mapper.readTree(raw).path("suggestions")) {
                        if (!c.path("target").asText().equals("/timeline")) continue;
                        List<String> validQuotes = new ArrayList<>();
                        List<Integer> failedQuotes = new ArrayList<>();
                        int quoteIndex = 0;
                        for (JsonNode quote : c.path("excerpts")) {
                            if (matcher.match(quote.asText()) != null) validQuotes.add(quote.asText()); else failedQuotes.add(quoteIndex);
                            quoteIndex++;
                        }
                        int nodeIndex = 0;
                        for (JsonNode node : c.path("value")) {
                            var reasons = new ArrayList<DocumentImportSuggestionValidator.Rejection>();
                            String probe = mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/timeline",
                                    "value", List.of(node), "excerpts", validQuotes))));
                            var tested = validator.validate(probe, DocumentImportTarget.ACTIVITY, source, id, reasons::add);
                            var row = new LinkedHashMap<String, Object>();
                            row.put("nodeIndex", nodeIndex++); row.put("node", node);
                            row.put("parentFailedExcerptIndices", failedQuotes);
                            row.put("isolatedValidWithLocatableParentEvidenceDiagnosticOnly", !tested.suggestions().isEmpty());
                            row.put("isolatedRejections", reasons); nodes.add(row);
                        }
                    }
                    if (directory.equals("target/document-import-time-live-samples") && id.equals("live-261b8ec9-1")) {
                        assertThat(nodes).hasSize(10).allMatch(n -> Boolean.TRUE.equals(n.get("isolatedValidWithLocatableParentEvidenceDiagnosticOnly")));
                        assertThat(finalResult.suggestions()).noneMatch(c -> c.target().equals("/timeline"));
                    }
                    if (directory.equals("target/document-import-time-live-samples") && id.equals("live-261b8ec9-2")) {
                        assertThat(nodes).hasSize(10);
                        assertThat(nodes.get(1).get("isolatedValidWithLocatableParentEvidenceDiagnosticOnly")).isEqualTo(false);
                        assertThat(nodes.stream().filter(n -> Boolean.TRUE.equals(n.get("isolatedValidWithLocatableParentEvidenceDiagnosticOnly"))).count()).isEqualTo(9);
                        assertThat(coverage.blocks()).anyMatch(b -> b.chapter().equals("工作要求") && b.status().equals("COVERAGE_NOT_ESTABLISHED"));
                        assertThat(finalResult.warnings()).anyMatch(w -> w.message().contains("覆盖依据不足"));
                    }
                    var row = new LinkedHashMap<String, Object>();
                    row.put("sample", file.toString()); row.put("importId", id); row.put("nodes", nodes);
                    row.put("initialRejections", rejected); row.put("rawTimelineNodes", nodes.size());
                    row.put("finalTimelineNodes", finalResult.suggestions().stream().filter(c -> c.target().equals("/timeline")).mapToInt(c -> c.value().size()).sum());
                    row.put("coverage", coverage); row.put("sectionsRecovery", recovery.mode());
                    row.put("finalWarnings", finalResult.warnings()); rows.add(row);
                }
            }
        }
        Path output = Path.of("target/document-import-round3-audit.json");
        Files.createDirectories(output.getParent()); mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), rows);
    }
}
