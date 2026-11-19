package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentImportEvidenceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DocumentImportSuggestionValidator validator = new DocumentImportSuggestionValidator(mapper);

    private DocumentImportSuggestionValidator.ValidationResult validate(String source, String excerpt) throws Exception {
        String raw = mapper.writeValueAsString(Map.of("suggestions", List.of(
                Map.of("target", "/summary", "value", "提交材料", "excerpts", List.of(excerpt)))));
        return validator.validate(raw, DocumentImportTarget.ACTIVITY, source, "evidence");
    }

    @Test
    void exactAndCrossLineExcerptsRemainUnchanged() throws Exception {
        for (String excerpt : List.of("提交材料", "提交\n材料")) {
            assertThat(validate(excerpt, excerpt).suggestions().get(0).excerpts()).containsExactly(excerpt);
        }
    }

    @Test
    void safeLayoutDifferencesRestoreTheOriginalContinuousSpan() throws Exception {
        for (String source : List.of("提交\n材料", "提交 材料", "提交\t材料", "提交\r\n材料")) {
            assertThat(validate(source, "提交材料").suggestions().get(0).excerpts()).containsExactly(source);
        }
        assertThat(validate("team  registration", "team registration").suggestions().get(0).excerpts())
                .containsExactly("team  registration");
        assertThat(validate("10 月 13 日，限 20 人", "10月13日，限20人").suggestions().get(0).excerpts())
                .containsExactly("10 月 13 日，限 20 人");
    }

    @Test
    void numbersConditionsPunctuationWordBoundariesAndPageMarkersCannotDisappear() throws Exception {
        for (String[] pair : List.of(
                new String[]{"限20人", "限30人"}, new String[]{"必须提交", "无需提交"},
                new String[]{"10:00", "10：00"}, new String[]{"1 0人", "10人"},
                new String[]{"限20人", "限20队"}, new String[]{"10\n12人", "1012人"},
                new String[]{"no registration", "noregistration"},
                new String[]{"提交\n— 2 —\n材料", "提交材料"},
                new String[]{"提交\f材料", "提交材料"}, new String[]{"提交\n\n材料", "提交材料"},
                new String[]{"提交其他材料", "提交材料"})) {
            assertThat(validate(pair[0], pair[1]).suggestions()).as(pair[0]).isEmpty();
        }
    }

    @Test
    void targetAndArrayFailuresAreLocatedWithoutAcceptingPartialArrays() {
        String raw = """
                {"suggestions":[
                  {"target":"title","value":"通知","excerpts":["通知"]},
                  {"target":"/sections","value":[{"title":"规则","content":"通知","format":"PLAIN_TEXT"},{"title":"缺失正文","format":"PLAIN_TEXT"}],"excerpts":["通知"]},
                  {"target":"/timeline","value":[{"type":"OTHER","title":"截止","schedule":{"kind":"EXACT_POINT","time":"2026-10-09T12:00:00"}}],"excerpts":["通知"]},
                  {"target":"/title","value":"通知","excerpts":["通知"]},
                  {"target":"/title","value":"通知","excerpts":["通知"]}
                ]}
                """;
        var rejected = new ArrayList<DocumentImportSuggestionValidator.Rejection>();
        var result = validator.validate(raw, DocumentImportTarget.ACTIVITY, "通知", "i", rejected::add);
        assertThat(result.suggestions()).extracting(DocumentImportSuggestionValidator.Candidate::target)
                .containsExactly("/title");
        assertThat(rejected).extracting(DocumentImportSuggestionValidator.Rejection::stage)
                .containsExactly("target", "array-schema", "array-schema", "target");
        assertThat(rejected.get(1).reason()).contains("[1]");
        assertThat(rejected.get(2).reason()).contains("[0]");
    }

    @Test
    void malformedEnvelopeTrailingTokensAndDuplicateJsonPropertiesAreRejected() {
        for (String raw : List.of("null", "[]", "{\"suggestions\":[]} {}",
                "{\"suggestions\":[],\"suggestions\":[]}", "{\"suggestions\":[],\"extra\":true}")) {
            org.junit.jupiter.api.Assertions.assertThrows(DocumentImportSuggestionValidator.InvalidModelOutputException.class,
                    () -> validator.validate(raw, DocumentImportTarget.ACTIVITY, "通知", "i"));
        }
    }

    @Test
    void matchDiagnosticsIdentifyExactAndLayoutModesWithoutIncludingText() throws Exception {
        String raw = mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/summary",
                "value", "提交材料", "excerpts", List.of("通知", "提交材料")))));
        var matches = new ArrayList<DocumentImportSuggestionValidator.EvidenceMatch>();
        var result = validator.validate(raw, DocumentImportTarget.ACTIVITY, "通知：提交\n材料", "i", rejected -> {}, matches::add);
        assertThat(matches).extracting(DocumentImportSuggestionValidator.EvidenceMatch::mode)
                .containsExactly("EXACT", "LAYOUT_WHITESPACE");
        assertThat(matches.get(1).sourceStart()).isEqualTo(3);
        assertThat(matches.get(1).sourceEnd()).isEqualTo(8);
        assertThat(result.suggestions().get(0).excerpts()).containsExactly("通知", "提交\n材料");
    }

    @Test
    void changedClockOrSecondsCannotBecomeMachineTime() throws Exception {
        for (var entry : List.of(
                Map.entry("2026年10月9日12:00:30+08:00", Map.of("kind", "EXACT_POINT", "time", "2026-10-09T12:00:00+08:00")),
                Map.entry("2026年10月9日12:00+08:00", Map.of("kind", "EXACT_POINT", "time", "2026-10-09T13:00:00+08:00")))) {
            String raw = mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/timeline",
                    "value", List.of(Map.of("type", "OTHER", "title", "截止", "schedule", entry.getValue())),
                    "excerpts", List.of(entry.getKey())))));
            assertThat(validator.validate(raw, DocumentImportTarget.ACTIVITY, entry.getKey(), "i").suggestions()).isEmpty();
        }
        String raw = """
                {"suggestions":[{"target":"/timeline","value":[{"type":"OTHER","title":"截止","schedule":{"kind":"TEXT","timeDescription":"10月9日12:00，未提供年份与时区"}}],"excerpts":["10月9日12:00"]}]}
                """;
        assertThat(validator.validate(raw, DocumentImportTarget.ACTIVITY, "10月9日12:00", "i").suggestions()).hasSize(1);
        String exact = """
                {"suggestions":[{"target":"/timeline","value":[{"type":"OTHER","title":"截止","schedule":{"kind":"EXACT_POINT","time":"2026-10-09T12:00:00Z"}}],"excerpts":["2026-10-09T12:00:00Z"]}]}
                """;
        assertThat(validator.validate(exact, DocumentImportTarget.ACTIVITY, "2026-10-09T12:00:00Z", "i").suggestions()).hasSize(1);
    }

    @Test
    void attachmentAndIncompleteOrExternalFormsAreRejected() {
        String raw = """
                {"suggestions":[
                  {"target":"/actions","value":[{"type":"VIEW_ATTACHMENT","title":"附件","description":"见附件一"}],"excerpts":["通知"]},
                  {"target":"/registrationMode","value":"EXTERNAL","excerpts":["通知"]},
                  {"target":"/registrationForm","value":{"allowModification":false,"fields":[{"label":"姓名","purpose":"NAME","type":"TEXT","required":true,"maxLength":80}]},"excerpts":["通知"]}
                ]}
                """;
        assertThat(validator.validate(raw, DocumentImportTarget.ACTIVITY, "通知", "i").suggestions())
                .extracting(DocumentImportSuggestionValidator.Candidate::target).containsExactly("/registrationMode");
        assertThat(validator.validate(raw.replace("\"allowModification\":false,", ""),
                DocumentImportTarget.ACTIVITY, "通知", "i").suggestions())
                .extracting(DocumentImportSuggestionValidator.Candidate::target).containsExactly("/registrationMode");
    }
}
