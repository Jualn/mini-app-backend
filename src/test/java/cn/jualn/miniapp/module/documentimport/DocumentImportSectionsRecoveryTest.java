package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentImportSectionsRecoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DocumentImportSuggestionValidator validator = new DocumentImportSuggestionValidator(mapper);

    private String raw(String content, String excerpt) throws Exception {
        return mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/sections",
                "value", List.of(Map.of("title", "要求", "content", content, "format", "PLAIN_TEXT")),
                "excerpts", List.of(excerpt)))));
    }

    private DocumentImportSectionsRecovery.Outcome recover(String raw, String source, DocumentImportTarget target) {
        var initial = validator.validate(raw, target, source, "test");
        return new DocumentImportSectionsRecovery(mapper, validator).recover(raw, target, source, "test", initial);
    }

    @Test void relocateOnlyWhenWholeTitleAndContentAreVerbatim() throws Exception {
        String source = "要求\n限20人，10月13日提交。";
        String raw = raw("限20人，10月13日提交。", "不存在的引用");
        var out = recover(raw, source, DocumentImportTarget.ACTIVITY);
        assertThat(out.mode()).isEqualTo("EXCERPTS_RELOCATED");
        assertThat(out.result().suggestions().get(0).value().at("/0/content").asText()).isEqualTo("限20人，10月13日提交。");
        assertThat(out.result().suggestions().get(0).excerpts()).containsExactly("要求", "限20人，10月13日提交。");
    }
    @Test void restoreMissingPageFooterInCitationWithoutReplacingOrganizedSections() throws Exception {
        String first = "参加选拔赛的运动员需要提前15分钟到比赛地点签到，";
        String last = "超过规定时间未到场按自动弃权处理。";
        String source = first + "\n— 2 —\n" + last;
        String organized = "须提前15分钟签到；未按时到场视为自动弃权。";
        var result = recover(raw(organized, first + "\n" + last), source, DocumentImportTarget.ACTIVITY);
        assertThat(result.mode()).isEqualTo("PAGE_FOOTER_EVIDENCE_RESTORED");
        assertThat(result.result().suggestions().get(0).value().at("/0/content").asText()).isEqualTo(organized);
        assertThat(result.result().suggestions().get(0).excerpts()).containsExactly(source);
    }
    @Test void missingBusinessRestrictionOrChangedNumberIsNotARecoverableFooter() throws Exception {
        String first = "参加选拔赛的运动员需要提前15分钟到比赛地点签到，";
        String last = "超过规定时间未到场按自动弃权处理。";
        for (String gap : List.of("仅限26级学生", "— 2026 —")) {
            var result = recover(raw("须提前15分钟签到，未到场按弃权处理。", first + "\n" + last),
                    first + "\n" + gap + "\n" + last, DocumentImportTarget.ACTIVITY);
            assertThat(result.mode()).isEqualTo("SOURCE_TEXT_FALLBACK");
        }
        var changed = recover(raw("须提前30分钟签到。", first.replace("15", "30") + "\n" + last),
                first + "\n— 2 —\n" + last, DocumentImportTarget.ACTIVITY);
        assertThat(changed.mode()).isEqualTo("SOURCE_TEXT_FALLBACK");
    }

    @Test void changedNumberConditionOrDiscontinuousContentNeverGetsFrozenRecovery() throws Exception {
        String source = "要求\n限20人，仅学生。参加\n- 3 -\n比赛。";
        for (String content : List.of("限30人，仅学生。", "限20人，所有人。", "参加比赛。")) {
            var out = recover(raw(content, "不存在"), source, DocumentImportTarget.ACTIVITY);
            assertThat(out.mode()).isEqualTo("SOURCE_TEXT_FALLBACK");
            assertThat(out.result().suggestions().get(0).value().at("/0/content").asText()).isEqualTo(source);
            assertThat(out.result().warnings()).anyMatch(w -> w.message().contains("需人工核对"));
        }
    }

    @Test void missingBodyIsSourceCopyForBothTargetTypes() {
        for (var target : DocumentImportTarget.values()) {
            var out = recover("{\"suggestions\":[]}", "原文\n材料要求", target);
            assertThat(out.mode()).isEqualTo("SOURCE_TEXT_FALLBACK");
            assertThat(out.result().suggestions().get(0).target()).isEqualTo("/sections");
            assertThat(out.result().suggestions().get(0).value().at("/0/content").asText()).isEqualTo("原文\n材料要求");
        }
    }

    @Test void matchingTextFromAnotherSectionCannotRepairAFalseHeadingAssociation() throws Exception {
        String source = "要求\n限20人。\n奖项\n一等奖1名。";
        var out = recover(raw("一等奖1名。", "不存在"), source, DocumentImportTarget.ACTIVITY);
        assertThat(out.mode()).isEqualTo("SOURCE_TEXT_FALLBACK");
        assertThat(out.result().suggestions().get(0).value().at("/0/content").asText()).isEqualTo(source);
    }

    @Test void validBodyAndOtherAcceptedBusinessFieldsStayIdentical() throws Exception {
        String raw = raw("限20人", "限20人");
        var initial = validator.validate(raw, DocumentImportTarget.ACTIVITY, "限20人", "test");
        assertThat(recover(raw, "限20人", DocumentImportTarget.ACTIVITY).result()).isEqualTo(initial);
        String noBody = "{\"suggestions\":[{\"target\":\"/title\",\"value\":\"通知\",\"excerpts\":[\"通知\"]}]}";
        var before = validator.validate(noBody, DocumentImportTarget.ACTIVITY, "通知", "test");
        assertThat(recover(noBody, "通知", DocumentImportTarget.ACTIVITY).result().suggestions()).containsAll(before.suggestions());
    }

    @Test void malformedElementIsNeverPartiallyAcceptedAndTimelineIsNotRepaired() {
        String raw = """
                {"suggestions":[
                  {"target":"/sections","value":[{"title":"要求","content":"原文","format":"PLAIN_TEXT"},{"title":"缺失"}],"excerpts":["原文"]},
                  {"target":"/timeline","value":[{"type":"OTHER","title":"阶段","schedule":{"kind":"EXACT_POINT","time":"2026-10-09T12:00:00Z"}}],"excerpts":["原文"]}
                ]}
                """;
        var out = recover(raw, "原文", DocumentImportTarget.ACTIVITY);
        assertThat(out.mode()).isEqualTo("SOURCE_TEXT_FALLBACK");
        assertThat(out.result().suggestions()).hasSize(1);
        assertThat(out.result().suggestions().get(0).value()).hasSize(1);
        assertThat(out.result().suggestions().get(0).value().at("/0/content").asText()).isEqualTo("原文");
    }

    @Test void fullRevalidationRejectsUnknownFieldsEvenWhenValuesMatch() {
        String raw = """
                {"suggestions":[{"target":"/sections","value":[{"title":"要求","content":"原文","format":"PLAIN_TEXT","extra":true}],"excerpts":["不存在"]}]}
                """;
        var out = recover(raw, "要求 原文", DocumentImportTarget.ACTIVITY);
        assertThat(out.mode()).isEqualTo("REVALIDATION_FAILED");
        assertThat(out.result().suggestions()).isEmpty();
    }

    @Test void duplicateBodyAndOversizedSourceFailClosed() throws Exception {
        String raw = raw("原文", "不存在");
        var root = mapper.readTree(raw);
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("suggestions")).add(root.path("suggestions").get(0).deepCopy());
        assertThat(recover(root.toString(), "要求 原文", DocumentImportTarget.ACTIVITY).mode()).isEqualTo("AMBIGUOUS_DUPLICATE");
        assertThat(recover(raw, "原".repeat(50001), DocumentImportTarget.ACTIVITY).mode()).isEqualTo("SOURCE_LIMIT");
    }

    @Test void largeSourceChunksStayExactAndPreserveSurrogates() {
        String source = "原".repeat(3999) + "😀" + "文".repeat(12000);
        var out = recover("{\"suggestions\":[]}", source, DocumentImportTarget.PUBLIC_EVENT);
        assertThat(out.result().suggestions().get(0).value().at("/0/content").asText()).isEqualTo(source);
        assertThat(String.join("", out.result().suggestions().get(0).excerpts())).isEqualTo(source);
        assertThat(out.result().suggestions().get(0).excerpts()).allSatisfy(e -> assertThat(e.length()).isLessThanOrEqualTo(4000));
    }
}
