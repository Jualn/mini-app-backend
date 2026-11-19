package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentImportSectionsCompletionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DocumentImportSuggestionValidator validator = new DocumentImportSuggestionValidator(mapper);
    private final String requirement = "各单位应组织筹备、广泛宣传，并及时总结成效，加强基层团组织建设。";
    private String raw(String content, List<String> quotes) throws Exception {
        return mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/sections", "excerpts", quotes,
                "value", List.of(Map.of("title", "参赛资格", "content", content, "format", "PLAIN_TEXT"))))));
    }
    @Test void appendsMissingRequirementsForBothTargetsAndKeepsPassedBodyExactly() throws Exception {
        String source = "一、参赛资格\n学院在校学生均可参赛，需所在单位推荐。\n二、工作要求\n" + requirement;
        String raw = raw("学院在校学生均可参赛，需所在单位推荐。", List.of("学院在校学生均可参赛，需所在单位推荐。"));
        for (var target : DocumentImportTarget.values()) {
            var initial = validator.validate(raw, target, source, "test");
            var result = new DocumentImportSectionsCompletion(mapper, validator).complete(raw, target, source, "test", initial, "UNCHANGED");
            assertThat(result.mode()).isEqualTo("MISSING_SOURCE_BLOCKS_APPENDED");
            var body = result.result().suggestions().get(0);
            assertThat(body.value()).hasSize(2);
            assertThat(body.value().get(0)).isEqualTo(initial.suggestions().get(0).value().get(0));
            assertThat(body.value().at("/1/content").asText()).isEqualTo(requirement);
            assertThat(body.value().at("/1/title").asText()).isEqualTo("工作要求（原文补充）");
            body.excerpts().forEach(excerpt -> assertThat(source).contains(excerpt));
        }
    }
    @Test void fullEvidenceOrLiteralCoverageDoesNotProduceDuplicateSections() throws Exception {
        String source = "一、工作要求\n" + requirement;
        for (String content : List.of(requirement, "各单位负责筹备宣传和总结，强化基层组织。")) {
            String raw = raw(content, List.of(requirement));
            var initial = validator.validate(raw, DocumentImportTarget.ACTIVITY, source, "test");
            assertThat(new DocumentImportSectionsCompletion(mapper, validator).complete(raw, DocumentImportTarget.ACTIVITY,
                    source, "test", initial, "UNCHANGED").mode()).isEqualTo("UNCHANGED");
        }
    }
    @Test void partialQuoteMustNotMaskTheRestOfTheRequirement() throws Exception {
        String source = "一、工作要求\n" + requirement;
        String raw = raw("各单位应组织筹备、广泛宣传。", List.of("各单位应组织筹备、广泛宣传"));
        var initial = validator.validate(raw, DocumentImportTarget.ACTIVITY, source, "test");
        var result = new DocumentImportSectionsCompletion(mapper, validator).complete(raw, DocumentImportTarget.ACTIVITY,
                source, "test", initial, "UNCHANGED");
        assertThat(result.appendedBlocks()).isEqualTo(1);
        assertThat(result.result().suggestions().get(0).value().at("/1/content").asText()).contains("总结成效", "基层团组织建设");
    }
    @Test void rawFallbackAndContactsAreNotTreatedAsMissingOrganizedChapters() throws Exception {
        String source = "一、工作要求\n" + requirement + "\n联系人及联系方式：\n测试联系人 13900000000";
        String raw = raw(requirement, List.of(requirement));
        var initial = validator.validate(raw, DocumentImportTarget.ACTIVITY, source, "test");
        var helper = new DocumentImportSectionsCompletion(mapper, validator);
        assertThat(helper.complete(raw, DocumentImportTarget.ACTIVITY, source, "test", initial, "SOURCE_TEXT_FALLBACK").mode()).isEqualTo("UNCHANGED");
        assertThat(helper.complete(raw, DocumentImportTarget.ACTIVITY, source, "test", initial, "UNCHANGED").result().suggestions().get(0).value()).hasSize(1);
    }
    @Test void splitScheduleTerminatorInTimelineDoesNotCauseAnExtraBodySchedule() throws Exception {
        String schedule = "3、2026年10月12日17:00—18:30：铅球、400米、800米\n";
        String source = "通知。\n一、赛程安排\n" + schedule + "。\n二、工作要求\n" + requirement;
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(raw(requirement, List.of(requirement)));
        var candidate = ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("suggestions")).addObject().put("target", "/timeline");
        candidate.putArray("excerpts").add(schedule).add("。");
        candidate.putArray("value").addObject().put("type", "OTHER").put("title", "铅球、400米、800米")
                .putObject("schedule").put("kind", "EXACT_RANGE")
                .put("startTime", "2026-10-12T17:00:00+08:00").put("endTime", "2026-10-12T18:30:00+08:00");
        var initial = validator.validate(root.toString(), DocumentImportTarget.ACTIVITY, source, "test");
        assertThat(initial.suggestions()).hasSize(2);
        var outcome = new DocumentImportSectionsCompletion(mapper, validator).complete(root.toString(), DocumentImportTarget.ACTIVITY,
                source, "test", initial, "UNCHANGED");
        assertThat(outcome.mode()).isEqualTo("UNCHANGED");
        assertThat(outcome.result()).isSameAs(initial);
    }
    @Test void pageFooterIsDiagnosticOnlyAndBusinessConditionsAreNeverIgnored() throws Exception {
        String first = "参加选拔赛的运动员需要提前15分钟到比赛地点签到，";
        String last = "超过规定时间未到场按自动弃权处理。";
        String raw = raw(first + last, List.of(first, last));
        for (String gap : List.of("— 2 —", "仅限26级学生", "— 2026 —")) {
            String source = "一、注意事项\n" + first + "\n" + gap + "\n" + last;
            var initial = validator.validate(raw, DocumentImportTarget.ACTIVITY, source, "test");
            var outcome = new DocumentImportSectionsCompletion(mapper, validator).complete(raw, DocumentImportTarget.ACTIVITY,
                    source, "test", initial, "UNCHANGED");
            assertThat(outcome.mode()).isEqualTo(gap.equals("— 2 —") ? "UNCHANGED" : "MISSING_SOURCE_BLOCKS_APPENDED");
            if (!gap.equals("— 2 —")) assertThat(outcome.result().suggestions().get(0).value().at("/1/content").asText()).contains(gap);
        }
        String badQuote = raw(first + last, List.of(first + last));
        assertThat(validator.validate(badQuote, DocumentImportTarget.ACTIVITY,
                "一、注意事项\n" + first + "\n— 2 —\n" + last, "test").suggestions()).isEmpty();
    }
}
