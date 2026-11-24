package cn.jualn.miniapp.module.documentimport;

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

/** Deterministic attempted schedules on private originals, not model generations or business writes. */
@EnabledIfSystemProperty(named = "documentImport.pdfDirectory", matches = ".+")
class DocumentImportRealPdfTimeTest {
    @Test void verifyActualTimePrecisionContextAndSeparateMaterialDeadlines() throws Exception {
        var mapper = new ObjectMapper();
        var validator = new DocumentImportSuggestionValidator(mapper);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String prefix : List.of("4c67ce08", "261b8ec9", "c7215d6b")) {
            Path sample;
            try (var files = Files.list(Path.of("target/document-import-samples"))) {
                sample = files.filter(p -> p.getFileName().toString().startsWith(prefix)).findFirst().orElseThrow();
            }
            var original = mapper.readTree(sample.toFile());
            Path pdf = Path.of(System.getProperty("documentImport.pdfDirectory"), original.path("filename").asText());
            String source = new DocumentImportFileReader().read(new MockMultipartFile("file", pdf.getFileName().toString(),
                    "application/pdf", Files.readAllBytes(pdf))).text();
            List<Case> cases = prefix.equals("4c67ce08") ? List.of(
                    new Case("11日下午第一时段", range("2026-10-11", "15:00", "16:30"), true, "EXACT_RANGE", ""),
                    new Case("11日下午第二时段", range("2026-10-11", "17:00", "18:30"), true, "EXACT_RANGE", ""),
                    new Case("12日下午时段", range("2026-10-12", "17:00", "18:30"), true, "EXACT_RANGE", ""),
                    new Case("报名截止上午12:00", point("2026-10-10", "12:00"), true, "EXACT_POINT", ""))
                    : prefix.equals("c7215d6b") ? List.of(
                    new Case("人才库材料截止上午12:00", point("2026-10-10", "12:00"), true, "EXACT_POINT", "")) : List.of(
                    new Case("学生组初赛日期", date("2026-10-13"), true, "DATE_POINT", "10月13日前"),
                    new Case("学生组决赛暂定日期不能确定化", date("2026-10-16"), false, "", "时间暂定"),
                    new Case("学生组决赛保留暂定", Map.of("kind", "TEXT", "timeDescription", "10月16日（时间暂定）"), true, "TEXT", ""),
                    new Case("教师组比赛保留变动条件", date("2026-10-14"), true, "DATE_POINT", "如有变动另行通知"),
                    new Case("学生电子材料", point("2026-10-13", "16:00"), true, "EXACT_POINT", "前提交电子档"),
                    new Case("学生纸质材料", range("2026-10-13", "17:00", "18:00"), true, "EXACT_RANGE", "交纸质档"),
                    new Case("教师电子材料", point("2026-10-12", "12:00"), true, "EXACT_POINT", "前发送电子档"),
                    new Case("教师纸质材料", point("2026-10-12", "16:00"), true, "EXACT_POINT", "前交纸质档"),
                    new Case("两个独立教师截止不能合并区间", range("2026-10-12", "12:00", "16:00"), false, "", ""));
            for (Case attempt : cases) {
                List<DocumentImportTimelineEvidence.TimeEvidence> traces = new ArrayList<>();
                var node = new LinkedHashMap<String, Object>();
                node.put("type", "OTHER"); node.put("title", attempt.label()); node.put("schedule", attempt.schedule());
                if (!attempt.description().isEmpty()) node.put("description", attempt.description());
                String raw = mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/timeline",
                        "value", List.of(node), "excerpts", List.of(source)))));
                var rejected = new ArrayList<DocumentImportSuggestionValidator.Rejection>();
                var result = validator.validate(raw, DocumentImportTarget.ACTIVITY, source, prefix,
                        rejected::add, ignored -> { }, traces::add);
                assertThat(!result.suggestions().isEmpty()).as(prefix + " " + attempt.label()).isEqualTo(attempt.accepted());
                if (attempt.accepted()) assertThat(result.suggestions().get(0).value().at("/0/schedule/kind").asText()).isEqualTo(attempt.finalKind());
                if (attempt.label().contains("上午12:00")) assertThat(result.warnings()).noneMatch(w -> "AMBIGUOUS_SOURCE".equals(w.code()));
                var row = new LinkedHashMap<String, Object>();
                row.put("input", prefix); row.put("case", attempt.label());
                row.put("attemptedSchedule", attempt.schedule()); row.put("deterministicFixtureNotModelOutput", true);
                row.put("timeEvidence", traces); row.put("accepted", !result.suggestions().isEmpty());
                row.put("finalTimeline", result.suggestions().isEmpty() ? List.of() : result.suggestions().get(0).value());
                row.put("rejected", rejected); row.put("warnings", result.warnings()); rows.add(row);
            }
        }
        Path output = Path.of("target/document-import-real-time-validation.json");
        Files.createDirectories(output.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), rows);
    }

    private Map<String, String> point(String date, String clock) { return Map.of("kind", "EXACT_POINT", "time", date + "T" + clock + ":00+08:00"); }
    private Map<String, String> date(String date) { return Map.of("kind", "DATE_POINT", "date", date); }
    private Map<String, String> range(String date, String start, String end) {
        return Map.of("kind", "EXACT_RANGE", "startTime", date + "T" + start + ":00+08:00", "endTime", date + "T" + end + ":00+08:00");
    }
    private record Case(String label, Map<String, String> schedule, boolean accepted, String finalKind, String description) { }
}
