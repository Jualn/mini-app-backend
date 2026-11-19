package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentImportTimelineEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DocumentImportSuggestionValidator validator = new DocumentImportSuggestionValidator(mapper);

    private DocumentImportSuggestionValidator.ValidationResult validate(String source, String type,
            Map<String, String> schedule, DocumentImportTarget target, List<DocumentImportTimelineEvidence.TimeEvidence> traces) throws Exception {
        String raw = mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/timeline", "value",
                List.of(Map.of("type", type, "title", "阶段", "schedule", schedule)), "excerpts", List.of(source)))));
        return validator.validate(raw, target, source, "t", ignored -> { }, ignored -> { }, traces::add);
    }

    private Map<String, String> exact(String time) { return Map.of("kind", "EXACT_POINT", "time", time); }

    @Test void legalStartTypesFollowFormalTargetAndRequireSourceMeaning() throws Exception {
        for (var target : DocumentImportTarget.values()) {
            String correct = target == DocumentImportTarget.ACTIVITY ? "ACTIVITY_START" : "PUBLIC_EVENT_START";
            String other = target == DocumentImportTarget.ACTIVITY ? "PUBLIC_EVENT_START" : "ACTIVITY_START";
            String source = target == DocumentImportTarget.ACTIVITY ? "活动于2026年10月11日下午三点开始。" : "考试于2026年10月11日下午三点开始。";
            assertThat(validate(source, correct, exact("2026-10-11T15:00:00+08:00"), target, new ArrayList<>()).suggestions()).hasSize(1);
            assertThat(validate(source, other, exact("2026-10-11T15:00:00+08:00"), target, new ArrayList<>()).suggestions()).isEmpty();
            assertThat(validate("2026年10月11日下午三点报名开始。", correct,
                    exact("2026-10-11T15:00:00+08:00"), target, new ArrayList<>()).suggestions()).isEmpty();
            var unchanged = validate(source, "OTHER", exact("2026-10-11T15:00:00+08:00"), target, new ArrayList<>());
            assertThat(unchanged.suggestions().get(0).value().at("/0/type").asText()).isEqualTo("OTHER");
        }
    }

    @Test void explicitStartCanStayTextAndPlainPrecisionCannotSupplyStartMeaning() throws Exception {
        assertThat(validate("活动开始时间另行通知。", "ACTIVITY_START", Map.of("kind", "TEXT", "timeDescription", "另行通知"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).hasSize(1);
        assertThat(validate("2026年10月11日赛事安排。", "ACTIVITY_START", Map.of("kind", "DATE_POINT", "date", "2026-10-11"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
    }

    @Test void chineseClocksMinutePrecisionAndConfiguredZoneAreTraceable() throws Exception {
        for (var entry : Map.of("下午三点", "15:00:00", "下午三点半", "15:30:00", "晚上八点", "20:00:00").entrySet()) {
            List<DocumentImportTimelineEvidence.TimeEvidence> traces = new ArrayList<>();
            var out = validate("2026年活动通知\n10月11日" + entry.getKey() + "进行比赛。", "OTHER",
                    exact("2026-10-11T" + entry.getValue() + "+08:00"), DocumentImportTarget.ACTIVITY, traces);
            assertThat(out.suggestions()).hasSize(1);
            assertThat(traces.get(0).yearSource()).startsWith("DOCUMENT_CONTEXT:SOURCE[");
            assertThat(traces.get(0).timezoneSource()).isEqualTo("SYSTEM_BUSINESS_ZONE:Asia/Shanghai");
            assertThat(traces.get(0).precision()).isEqualTo("MINUTE");
        }
    }

    @Test void numericRangesMustBeSourceIntervalsRatherThanTwoIndependentDeadlines() throws Exception {
        Map<String, String> range = Map.of("kind", "EXACT_RANGE", "startTime", "2026-10-11T15:00:00+08:00", "endTime", "2026-10-11T16:30:00+08:00");
        assertThat(validate("2026年活动通知\n10月11日15:00—16:30比赛。", "OTHER", range,
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).hasSize(1);
        assertThat(validate("2026年活动通知\n10月11日15:00电子档截止；10月11日16:30纸质档截止。", "OTHER", range,
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
        assertThat(validate("活动开始：2026年10月11日15:00—16:30。", "ACTIVITY_START", exact("2026-10-11T16:30:00+08:00"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
    }

    @Test void conflictingYearsCannotSupplyAnEventYear() throws Exception {
        for (String source : List.of("2026年活动通知\n2027年活动通知\n10月11日15:00比赛。")) {
            assertThat(validate(source, "OTHER", exact("2026-10-11T15:00:00+08:00"),
                    DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
        }
    }

    @Test void changedDayClockOffsetOrSecondsNeverGetsAccepted() throws Exception {
        String source = "2026年10月11日15:30:25+08:00比赛。";
        for (String wrong : List.of("2026-10-12T15:30:25+08:00", "2026-10-11T15:31:25+08:00",
                "2026-10-11T15:30:25+07:00", "2026-10-11T15:30:00+08:00")) {
            assertThat(validate(source, "OTHER", exact(wrong), DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
        }
        assertThat(validate(source, "OTHER", exact("2026-10-11T15:30:25+08:00"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).hasSize(1);
        assertThat(validate("2026年10月11日15:30比赛。", "OTHER", exact("2026-10-11T15:30:01+08:00"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
    }

    @Test void dateOnlyAndTentativeTimesKeepTheirPrecision() throws Exception {
        assertThat(validate("2026年活动通知\n10月11日比赛。", "OTHER", Map.of("kind", "DATE_POINT", "date", "2026-10-11"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).hasSize(1);
        for (String source : List.of("2026年活动通知\n10月11日比赛。", "2026年10月11日15:00（暂定）。",
                "2026年活动通知\n10月中旬比赛。", "2026年10月11日具体时间另行通知。")) {
            assertThat(validate(source, "OTHER", exact("2026-10-11T15:00:00+08:00"),
                    DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
        }
        assertThat(validate("2026年10月11日（暂定）。", "OTHER", Map.of("kind", "DATE_POINT", "date", "2026-10-11"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
    }

    @Test void numericTwelveIsNoonForEveryPeriodAndNeverMidnight() throws Exception {
        for (String period : List.of("", "上午", "下午", "凌晨", "中午")) {
            String source = "2026年10月10日" + period + "12:00之前提交。";
            var out = validate(source, "MATERIAL_SUBMISSION", exact("2026-10-10T12:00:00+08:00"),
                    DocumentImportTarget.ACTIVITY, new ArrayList<>());
            assertThat(out.suggestions()).hasSize(1);
            assertThat(out.suggestions().get(0).value().at("/0/schedule/kind").asText()).isEqualTo("EXACT_POINT");
            assertThat(out.warnings()).noneMatch(w -> "AMBIGUOUS_SOURCE".equals(w.code()));
            assertThat(validate(source, "MATERIAL_SUBMISSION", exact("2026-10-10T00:00:00+08:00"),
                    DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
        }
    }

    @Test void missingYearUsesShanghaiBusinessYearAndExplicitContextWins() throws Exception {
        var fixed = java.time.Clock.fixed(java.time.Instant.parse("2026-12-31T16:30:00Z"), java.time.ZoneOffset.UTC);
        var timeline = (com.fasterxml.jackson.databind.node.ArrayNode) mapper.readTree("""
                [{"type":"OTHER","title":"比赛","schedule":{"kind":"EXACT_POINT","time":"2027-10-11T15:00:00+08:00"}}]
                """);
        List<DocumentImportTimelineEvidence.TimeEvidence> traces = new ArrayList<>();
        new DocumentImportTimelineEvidence("10月11日15:00比赛。", fixed)
                .validate(timeline, List.of("10月11日15:00比赛。"), traces::add, ignored -> { });
        assertThat(traces.get(0).yearSource()).isEqualTo("SYSTEM_CURRENT_BUSINESS_YEAR:Asia/Shanghai:2027");
        var wrongYear = timeline.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongYear.get(0).get("schedule"))
                .put("time", "2026-10-11T15:00:00+08:00");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new DocumentImportTimelineEvidence("10月11日15:00比赛。", fixed)
                        .validate(wrongYear, List.of("10月11日15:00比赛。"), ignored -> { }, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(validate("2025年活动通知\n10月11日15:00比赛。", "OTHER", exact("2025-10-11T15:00:00+08:00"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).hasSize(1);
        assertThat(validate("2025年活动通知\n10月11日15:00比赛。", "OTHER", exact("2027-10-11T15:00:00+08:00"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
    }

    @Test void promptTypeSetsAndTimePolicyMatchEachTarget() {
        String activity = new DocumentImportPromptBuilder().build(DocumentImportTarget.ACTIVITY, "PDF_TEXT_LAYER", "通知");
        String event = new DocumentImportPromptBuilder().build(DocumentImportTarget.PUBLIC_EVENT, "PDF_TEXT_LAYER", "通知");
        assertThat(activity).contains("type=" + cn.jualn.miniapp.module.timeline.model.TimelineSemantic.ACTIVITY_INPUT_PATTERN);
        assertThat(event).contains("type=" + cn.jualn.miniapp.module.timeline.model.TimelineSemantic.PUBLIC_EVENT_INPUT_PATTERN);
        assertThat(activity).contains("可信系统业务时区为 Asia/Shanghai", "12:00固定解释为中午", "原文必须明确活动/事项开始语义");
    }

    @Test void asciiRangeAndExplicitNegativeOffsetsStayDistinct() throws Exception {
        assertThat(validate("2026年10月11日17:00-18:00比赛。", "OTHER", Map.of("kind", "EXACT_RANGE",
                "startTime", "2026-10-11T17:00:00+08:00", "endTime", "2026-10-11T18:00:00+08:00"),
                DocumentImportTarget.PUBLIC_EVENT, new ArrayList<>()).suggestions()).hasSize(1);
        assertThat(validate("2026-10-11T15:00:00-07:00比赛。", "OTHER", exact("2026-10-11T15:00:00-07:00"),
                DocumentImportTarget.PUBLIC_EVENT, new ArrayList<>()).suggestions()).hasSize(1);
        for (String wrong : List.of("2026-10-11T15:00:00+08:00", "2026-10-11T07:00:00-07:00")) {
            assertThat(validate("2026-10-11T15:00:00-07:00比赛。", "OTHER", exact(wrong),
                    DocumentImportTarget.PUBLIC_EVENT, new ArrayList<>()).suggestions()).isEmpty();
        }
        assertThat(validate("2026年10月11日上午15:00比赛。", "OTHER", exact("2026-10-11T15:00:00+08:00"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
        assertThat(validate("2006年10月1日出生人员参加。10月11日15:00比赛。", "OTHER", exact("2006-10-11T15:00:00+08:00"),
                DocumentImportTarget.ACTIVITY, new ArrayList<>()).suggestions()).isEmpty();
    }
}
