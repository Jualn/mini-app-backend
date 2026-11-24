package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentImportTimelineRecoveryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DocumentImportSuggestionValidator validator = new DocumentImportSuggestionValidator(mapper);
    private final String source = "2026年10月13日16:00前提交学生组电子材料。";
    private String raw(String quote, String time) throws Exception {
        return mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/timeline", "value",
                List.of(Map.of("type", "MATERIAL_SUBMISSION", "title", "学生电子材料", "description", "16:00前提交学生组电子材料。",
                        "schedule", Map.of("kind", "EXACT_POINT", "time", time))), "excerpts", List.of(quote)))));
    }
    private DocumentImportTimelineRecovery.Outcome recover(String raw, String response) {
        return new DocumentImportTimelineRecovery(mapper, validator).recover(raw, DocumentImportTarget.ACTIVITY, source, "test",
                validator.validate(raw, DocumentImportTarget.ACTIVITY, source, "test"), ignored -> response, () -> false);
    }
    @Test void repairsEvidenceWithFrozenFactsAndFullRevalidation() throws Exception {
        String original = raw("2026年10月13日16:00前交学生电子资料。", "2026-10-13T16:00:00+08:00");
        String fixed = raw(source, "2026-10-13T16:00:00+08:00");
        var outcome = recover(original, fixed);
        assertThat(outcome.mode()).isEqualTo("EVIDENCE_REPAIRED");
        assertThat(outcome.modelCalls()).isEqualTo(1);
        assertThat(outcome.result().suggestions()).hasSize(1);
        assertThat(mapper.readTree(outcome.raw()).at("/suggestions/0/value")).isEqualTo(mapper.readTree(original).at("/suggestions/0/value"));
    }
    @Test void changedValueOrUnsupportedTimeAndEvidenceCannotBeAccepted() throws Exception {
        String original = raw("错误引用", "2026-10-13T16:00:00+08:00");
        for (String response : List.of(raw(source, "2026-10-13T17:00:00+08:00"), raw("不在原文", "2026-10-13T16:00:00+08:00")))
            assertThat(recover(original, response).result().suggestions()).isEmpty();
        assertThat(recover(raw("错误引用", "2026-10-13T17:00:00+08:00"), raw(source, "2026-10-13T17:00:00+08:00"))
                .result().suggestions()).isEmpty();
    }
    @Test void malformedResponseOrProviderFailureKeepsOriginalPassedFields() throws Exception {
        String original = raw("错误引用", "2026-10-13T16:00:00+08:00");
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(original);
        ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("suggestions")).addObject()
                .put("target", "/title").put("value", "材料通知").putArray("excerpts").add(source);
        var initial = validator.validate(root.toString(), DocumentImportTarget.ACTIVITY, source, "test");
        var outcome = new DocumentImportTimelineRecovery(mapper, validator).recover(root.toString(), DocumentImportTarget.ACTIVITY,
                source, "test", initial, ignored -> { throw new IllegalStateException("provider unavailable"); }, () -> false);
        assertThat(outcome.result()).isSameAs(initial);
        assertThat(outcome.modelCalls()).isEqualTo(1);
        assertThat(recover(original, "{not json").result().suggestions()).isEmpty();
    }
    @Test void oneWrongNodeStillRejectsTheCompleteArrayAndUnsupportedLocationIsNotRescued() throws Exception {
        var original = mapper.readTree(raw("错误引用", "2026-10-13T16:00:00+08:00"));
        var nodes = (com.fasterxml.jackson.databind.node.ArrayNode) original.at("/suggestions/0/value");
        var wrong = nodes.get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrong.get("schedule")).put("time", "2026-10-13T17:00:00+08:00");
        nodes.add(wrong);
        var fixed = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) fixed.at("/suggestions/0")).putArray("excerpts").add(source);
        assertThat(recover(original.toString(), fixed.toString()).result().suggestions()).isEmpty();
        nodes.remove(1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) nodes.get(0)).put("location", "活动中心999办公室");
        fixed = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) fixed.at("/suggestions/0")).putArray("excerpts").add(source);
        assertThat(recover(original.toString(), fixed.toString()).result().suggestions()).isEmpty();
    }
    @Test void cancellationAndDuplicateCandidatesNeverCallModel() throws Exception {
        String original = raw("错误引用", "2026-10-13T16:00:00+08:00");
        AtomicInteger calls = new AtomicInteger();
        var helper = new DocumentImportTimelineRecovery(mapper, validator);
        helper.recover(original, DocumentImportTarget.ACTIVITY, source, "test", validator.validate(original, DocumentImportTarget.ACTIVITY, source, "test"),
                ignored -> { calls.incrementAndGet(); return original; }, () -> true);
        var root = mapper.readTree(original);
        var candidates = (com.fasterxml.jackson.databind.node.ArrayNode) root.get("suggestions");
        candidates.add(candidates.get(0).deepCopy());
        helper.recover(root.toString(), DocumentImportTarget.ACTIVITY, source, "test", validator.validate(root.toString(), DocumentImportTarget.ACTIVITY, source, "test"),
                ignored -> { calls.incrementAndGet(); return original; }, () -> false);
        assertThat(calls).hasValue(0);
    }
    @Test void preparationTypeCorrectionKeepsAllNodesAndDoesNotMixTargets() throws Exception {
        String text = "启动阶段（9月下旬）：宣传、动员，做好赛事准备。";
        String raw = mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/timeline", "excerpts", List.of(text),
                "value", List.of(Map.of("type", "ACTIVITY_START", "title", "启动阶段", "description", "宣传、动员，做好赛事准备。",
                        "schedule", Map.of("kind", "TEXT", "timeDescription", "9月下旬")))))));
        for (var target : DocumentImportTarget.values()) {
            var initial = validator.validate(raw, target, text, "test");
            var outcome = new DocumentImportTimelineRecovery(mapper, validator).recover(raw, target, text, "test", initial,
                    ignored -> { throw new AssertionError("must not call model"); }, () -> false);
            if (target == DocumentImportTarget.ACTIVITY)
                assertThat(outcome.result().suggestions().get(0).value().at("/0/type").asText()).isEqualTo("OTHER");
            else assertThat(outcome.result().suggestions()).isEmpty();
        }
    }
}
