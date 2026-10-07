package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentImportSuggestionValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DocumentImportSuggestionValidator validator = new DocumentImportSuggestionValidator(mapper);

    @Test
    void activityAcceptsFiveTimeBranchesAndCompleteComplexValues() {
        String source = "标题 活动 日期 精确点 精确段 日期点 日期段 另行通知 规则 姓名 上午 邮件 help@example.org";
        String raw = """
                {"suggestions":[
                  {"target":"/title","value":"标题 活动","excerpts":["标题 活动"]},
                  {"target":"/timeline","value":[
                    {"type":"REGISTRATION_END","title":"精确点","schedule":{"kind":"EXACT_POINT","time":"2027-03-15T18:00:00+08:00"}},
                    {"type":"OTHER","title":"精确段","schedule":{"kind":"EXACT_RANGE","startTime":"2027-03-20T09:00:00+08:00","endTime":"2027-03-20T11:00:00+08:00"}},
                    {"type":"EXAM","title":"日期点","schedule":{"kind":"DATE_POINT","date":"2027-04-20"}},
                    {"type":"CERTIFICATE_COLLECTION","title":"日期段","schedule":{"kind":"DATE_RANGE","startDate":"2027-05-01","endDate":"2027-05-07"}},
                    {"type":"RESULT","title":"结果","schedule":{"kind":"TEXT","timeDescription":"另行通知"}}
                  ],"excerpts":["精确点 精确段 日期点 日期段 另行通知"]},
                  {"target":"/sections","value":[{"title":"规则","content":"规则","format":"PLAIN_TEXT"}],"excerpts":["规则"]},
                  {"target":"/actions","value":[{"type":"EMAIL_SUBMISSION","title":"邮件","url":"mailto:help@example.org"}],"excerpts":["邮件 help@example.org"]},
                  {"target":"/contacts","value":[{"name":"邮件","contact":"help@example.org"}],"excerpts":["邮件 help@example.org"]},
                  {"target":"/registrationMode","value":"MINI_PROGRAM","excerpts":["规则"]},
                  {"target":"/participantMode","value":"INDIVIDUAL","excerpts":["规则"]},
                  {"target":"/registrationForm","value":{"allowModification":true,"fields":[
                    {"label":"姓名","purpose":"NAME","type":"TEXT","required":true,"maxLength":80},
                    {"label":"场次","purpose":"CUSTOM","type":"SINGLE_SELECT","required":true,"options":[{"label":"上午"}]}
                  ]},"excerpts":["姓名 上午"]}
                ]}
                """;

        var result = validator.validate(raw, DocumentImportTarget.ACTIVITY, source, "import-a");
        assertThat(result.warnings()).isEmpty();
        assertThat(result.suggestions()).hasSize(8);
        var timeline = result.suggestions().get(1).value();
        assertThat(timeline).hasSize(5);
        assertThat(timeline.get(0).path("nodeKey").asText()).startsWith("ai-");
        assertThat(timeline.get(0).path("displayOrder").asInt()).isZero();
        assertThat(timeline.get(4).at("/schedule/kind").asText()).isEqualTo("TEXT");
        var form = result.suggestions().get(7).value();
        assertThat(form.at("/fields/0/fieldKey").asText()).startsWith("ai-");
        assertThat(form.at("/fields/1/options/0/optionKey").asText()).startsWith("ai-");
    }

    @Test
    void publicEventUsesItsOwnTargetsAndRejectsActivityOnlyTarget() {
        String source = "资格考试通知 示例考试中心 https://example.org/exam";
        String raw = """
                {"suggestions":[
                  {"target":"/type","value":"EXAM","excerpts":["资格考试通知"]},
                  {"target":"/sourceName","value":"示例考试中心","excerpts":["示例考试中心"]},
                  {"target":"/officialUrl","value":"https://example.org/exam","excerpts":["https://example.org/exam"]},
                  {"target":"/registrationMode","value":"NONE","excerpts":["资格考试通知"]}
                ]}
                """;
        var result = validator.validate(raw, DocumentImportTarget.PUBLIC_EVENT, source, "import-p");
        assertThat(result.suggestions()).extracting(DocumentImportSuggestionValidator.Candidate::target)
                .containsExactly("/type", "/sourceName", "/officialUrl");
        assertThat(result.warnings()).hasSize(1);
    }

    @Test
    void rejectsWholeInvalidOutputAndOmitsInvalidOrUnsupportedCandidates() {
        assertThrows(DocumentImportSuggestionValidator.InvalidModelOutputException.class,
                () -> validator.validate("not-json", DocumentImportTarget.ACTIVITY, "source", "i"));
        String raw = """
                {"suggestions":[
                  {"target":"/timeline","value":[{"type":"OTHER","title":"逆序","schedule":{"kind":"DATE_RANGE","startDate":"2027-05-07","endDate":"2027-05-01"}}],"excerpts":["source"]},
                  {"target":"/actions","value":[{"type":"VIEW_ATTACHMENT","title":"附件","attachmentId":"123"}],"excerpts":["source"]},
                  {"target":"/title","value":"not supported","excerpts":["missing excerpt"]}
                ]}
                """;
        var result = validator.validate(raw, DocumentImportTarget.ACTIVITY, "source", "i");
        assertThat(result.suggestions()).isEmpty();
        assertThat(result.warnings()).hasSize(3).allMatch(w -> "INVALID_SUGGESTION".equals(w.code()));
    }

    @Test
    void capacityPairAndRegistrationCrossFieldConflictsAreNotEmitted() {
        String source = "团队 平台报名 一百人";
        String raw = """
                {"suggestions":[
                  {"target":"/registrationMode","value":"MINI_PROGRAM","excerpts":["平台报名"]},
                  {"target":"/participantMode","value":"TEAM","excerpts":["团队"]},
                  {"target":"/capacity","value":100,"excerpts":["一百人"]}
                ]}
                """;
        var result = validator.validate(raw, DocumentImportTarget.ACTIVITY, source, "i");
        assertThat(result.suggestions()).extracting(DocumentImportSuggestionValidator.Candidate::target)
                .containsExactly("/registrationMode");
        assertThat(result.warnings()).extracting(DocumentImportSuggestionValidator.Warning::code)
                .containsOnly("INCOMPLETE_CONFIGURATION");
    }
}
