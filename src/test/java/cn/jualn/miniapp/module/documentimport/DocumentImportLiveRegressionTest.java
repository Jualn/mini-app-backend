package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.config.WebClientConfig;
import cn.jualn.miniapp.third.ai.client.DeepSeekClient;
import cn.jualn.miniapp.third.ai.config.DeepSeekProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit opt-in only: six initial calls plus at most one evidence repair each; no activity writes. */
@EnabledIfEnvironmentVariable(named = "DOCUMENT_IMPORT_LIVE_TEST", matches = "true")
class DocumentImportLiveRegressionTest {
    @Test
    @Timeout(1800)
    void repeatTheSameThreeExtractedInputsTwice() throws Exception {
        String apiKey = System.getenv("DEEP_SEEK_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(), "Model test credential is unavailable");
        ObjectMapper mapper = new ObjectMapper();
        DeepSeekClient client = new DeepSeekClient(new DeepSeekProperties(apiKey), mapper,
                new WebClientConfig().aiWebClient(mapper, new DocumentImportProperties()));
        var diagnosticsProperties = new DocumentImportProperties();
        diagnosticsProperties.setDiagnosticsEnabled(true);
        String outputDirectory = System.getProperty("documentImport.liveOutputDirectory", "target/document-import-live-samples");
        diagnosticsProperties.setDiagnosticsDirectory(outputDirectory);
        var diagnostics = new DocumentImportDiagnostics(mapper, diagnosticsProperties);
        List<Map<String, Object>> report = new ArrayList<>();
        Path reportFile = Path.of(outputDirectory + "-regression.json");
        try {
            for (String prefix : List.of("4c67ce08", "261b8ec9", "c7215d6b")) {
                Path file;
                try (var files = Files.list(Path.of("target/document-import-samples"))) {
                    file = files.filter(path -> path.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow();
                }
                var input = mapper.readTree(file.toFile());
                String source = input.path("extractedText").asText();
                if (System.getProperty("documentImport.pdfDirectory") != null) {
                    Path pdf = Path.of(System.getProperty("documentImport.pdfDirectory"), input.path("filename").asText());
                    source = new DocumentImportFileReader().read(new MockMultipartFile("file", pdf.getFileName().toString(),
                            "application/pdf", Files.readAllBytes(pdf))).text();
                }
                final String extractedSource = source;
                String prompt = new DocumentImportPromptBuilder().build(DocumentImportTarget.ACTIVITY, "PDF_TEXT_LAYER", source);
                for (int repeat = 1; repeat <= 2; repeat++) {
                    String id = "live-" + prefix + "-" + repeat;
                    Path previousFile = Path.of(outputDirectory, id + ".json");
                    if (Files.exists(previousFile) && !"validated".equals(mapper.readTree(previousFile.toFile()).path("outcome").asText())) {
                        // Preserve interrupted experiment evidence; a new explicit experiment has its own identity.
                        id += "-continuation";
                        previousFile = Path.of(outputDirectory, id + ".json");
                    }
                    var sample = diagnostics.begin(id, input.path("filename").asText(), DocumentImportTarget.ACTIVITY);
                    sample.put("prompt", prompt);
                    sample.put("extractedText", source);
                    sample.put("model", client.modelName());
                    boolean cached = false;
                    long callStarted = System.nanoTime();
                    final Path recoveryCacheFile = previousFile;
                    try {
                        String raw;
                        if (Files.exists(previousFile)) {
                            var previous = mapper.readTree(previousFile.toFile());
                            assertThat(previous.path("prompt").asText()).isEqualTo(prompt);
                            assertThat(previous.path("model").asText()).isEqualTo(client.modelName());
                            assertThat(previous.path("outcome").asText()).isEqualTo("validated");
                            raw = previous.path("rawModelOutput").asText();
                            cached = true; // Revalidate completed calls without spending again or replacing their evidence.
                        } else {
                            raw = client.generate(prompt);
                        }
                        sample.put("rawModelOutput", raw);
                        var rejected = new ArrayList<DocumentImportSuggestionValidator.Rejection>();
                        var matches = new ArrayList<DocumentImportSuggestionValidator.EvidenceMatch>();
                        var times = new ArrayList<DocumentImportTimelineEvidence.TimeEvidence>();
                        var result = new DocumentImportSuggestionValidator(mapper).validate(raw, DocumentImportTarget.ACTIVITY,
                                source, id, rejected::add, matches::add, times::add);
                        sample.put("initialValidation", result);
                        long recoveryStarted = System.nanoTime();
                        final boolean replaying = cached;
                        final com.fasterxml.jackson.databind.JsonNode citationCache = replaying
                                ? mapper.readTree(recoveryCacheFile.toFile()).path("timelineCitationRepair")
                                : mapper.getNodeFactory().missingNode();
                        var timelineRecovered = new DocumentImportTimelineRecovery(mapper, new DocumentImportSuggestionValidator(mapper))
                                .recover(raw, DocumentImportTarget.ACTIVITY, source, id, result,
                                        frozen -> replaying ? citationCache.toString() : client.generate(
                                                new DocumentImportPromptBuilder().buildTimelineEvidenceRepair(
                                                        DocumentImportTarget.ACTIVITY, "PDF_TEXT_LAYER", extractedSource, frozen)),
                                        () -> replaying && citationCache.isMissingNode());
                        if (timelineRecovered.modelCalls() > 0 && timelineRecovered.result().suggestions().stream()
                                .anyMatch(c -> "/timeline".equals(c.target()))) {
                            var envelope = mapper.createObjectNode();
                            var repairedCandidate = envelope.putArray("suggestions");
                            mapper.readTree(timelineRecovered.raw()).path("suggestions").forEach(candidate -> {
                                if ("/timeline".equals(candidate.path("target").asText())) repairedCandidate.add(candidate);
                            });
                            sample.put("timelineCitationRepair", envelope);
                        }
                        result = timelineRecovered.result();
                        var recovered = new DocumentImportSectionsRecovery(mapper, new DocumentImportSuggestionValidator(mapper))
                                .recover(timelineRecovered.raw(), DocumentImportTarget.ACTIVITY, source, id, result);
                        result = recovered.result();
                        var completion = new DocumentImportSectionsCompletion(mapper, new DocumentImportSuggestionValidator(mapper))
                                .complete(timelineRecovered.raw(), DocumentImportTarget.ACTIVITY, source, id, result, recovered.mode());
                        result = completion.result();
                        sample.put("recoveryMicros", (System.nanoTime() - recoveryStarted) / 1000);
                        long coverageStarted = System.nanoTime();
                        var coverageChecker = new DocumentImportContentCoverage();
                        var coverage = coverageChecker.inspect(source, result, recovered.mode());
                        result = coverageChecker.warn(result, coverage);
                        sample.put("contentCoverage", coverage);
                        sample.put("contentCoverageMicros", (System.nanoTime() - coverageStarted) / 1000);
                        sample.put("sectionsRecovery", recovered.mode());
                        sample.put("timelineRecovery", timelineRecovered.mode());
                        sample.put("timelineRecoveryMillis", timelineRecovered.elapsedMillis());
                        sample.put("sectionsCompletion", completion.mode());
                        sample.put("sectionsAppendedBlocks", completion.appendedBlocks());
                        sample.put("recoveryModelCalls", replaying ? 0 : timelineRecovered.modelCalls());
                        sample.put("recoveryReplayedFromCache", replaying && timelineRecovered.modelCalls() > 0);
                        sample.put("elapsedMillis", (System.nanoTime() - callStarted) / 1000000);
                        result.suggestions().forEach(candidate -> candidate.excerpts().forEach(excerpt -> assertThat(extractedSource).contains(excerpt)));
                        sample.put("validation", result);
                        sample.put("rejected", rejected);
                        sample.put("evidenceMatches", matches);
                        sample.put("timeEvidence", times);
                        sample.put("outcome", "validated");
                        report.add(Map.of("input", prefix, "repeat", repeat,
                                "rawCandidates", mapper.readTree(raw).path("suggestions").size(),
                                "accepted", result.suggestions().size(), "rejected", rejected,
                                "targets", result.suggestions().stream().map(DocumentImportSuggestionValidator.Candidate::target).toList()));
                    } catch (WebClientException exception) {
                        sample.put("outcome", "model-environment-unavailable");
                        sample.put("exceptionType", exception.getClass().getSimpleName());
                        if (exception instanceof org.springframework.web.reactive.function.client.WebClientResponseException response) {
                            sample.put("httpStatus", response.getStatusCode().value());
                        }
                        report.add(Map.of("input", prefix, "repeat", repeat, "outcome", "BLOCKED",
                                "exceptionType", exception.getClass().getSimpleName()));
                        assumeTrue(false, "Model environment blocked: " + exception.getClass().getSimpleName());
                    } finally {
                        if (!cached) diagnostics.write(sample);
                    }
                }
            }
        } finally {
            Files.createDirectories(reportFile.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), report);
        }
    }
}
