package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentImportContentCoverageTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DocumentImportSuggestionValidator validator = new DocumentImportSuggestionValidator(mapper);
    private final DocumentImportContentCoverage checker = new DocumentImportContentCoverage();
    private DocumentImportSuggestionValidator.ValidationResult body(String source, String content, String quote,
            DocumentImportTarget target) throws Exception {
        return validator.validate(mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/sections",
                "value", List.of(Map.of("title", "合并正文", "content", content, "format", "PLAIN_TEXT")), "excerpts", List.of(quote))))),
                target, source, "test");
    }

    @Test void repeatedHeadingKeywordsDoNotCoverMissingDuties() throws Exception {
        String source = "一、材料提交\n学生组须在指定时间提交完整报名材料。\n二、工作要求\n各单位应组织宣传并总结本单位工作成效。";
        var result = body(source, "工作要求 工作要求 工作要求\n学生组须在指定时间提交完整报名材料。", "学生组须在指定时间提交完整报名材料。", DocumentImportTarget.ACTIVITY);
        var assessment = checker.inspect(source, result, "UNCHANGED");
        assertThat(assessment.state()).isEqualTo("ACCEPTED_COVERAGE_REQUIRES_REVIEW");
        assertThat(assessment.blocks()).anyMatch(b -> b.chapter().equals("工作要求") && b.status().equals("COVERAGE_NOT_ESTABLISHED"));
        var warned = checker.warn(result, assessment);
        assertThat(warned.suggestions()).isEqualTo(result.suggestions());
        assertThat(warned.warnings()).anyMatch(w -> w.code().equals("INCOMPLETE_CONFIGURATION") && w.message().contains("已通过") && w.message().contains("覆盖依据不足"));
    }

    @Test void mergedSectionsCanLocateCompleteSourceFactsForBothTargets() throws Exception {
        String first = "所有参赛人员必须由所在单位推荐参赛。";
        String second = "学生电子材料必须在指定日期截止前发送。";
        String source = "一、参赛资格\n" + first + "\n二、材料提交\n" + second;
        for (var target : DocumentImportTarget.values()) {
            var result = body(source, first + "\n" + second, source, target);
            var assessment = checker.inspect(source, result, "UNCHANGED");
            assertThat(assessment.blocks()).hasSize(2).allMatch(b -> b.status().equals("VERBATIM_VALUE"));
            assertThat(assessment.state()).isEqualTo("EVIDENCE_LOCATED_SEMANTIC_REVIEW_REQUIRED");
            assertThat(checker.warn(result, assessment)).isEqualTo(result);
        }
    }

    @Test void paraphraseWithEvidenceStaysUnknownRatherThanClaimedCompleteOrMissing() throws Exception {
        String source = "一、参赛资格\n所有参赛人员必须由所在单位推荐参赛。";
        var result = body(source, "参赛者需由所在单位推荐。", source, DocumentImportTarget.ACTIVITY);
        assertThat(checker.inspect(source, result, "UNCHANGED").blocks()).allMatch(b -> b.status().equals("CITED_REQUIRES_VALUE_REVIEW"));
    }

    @Test void sourceFallbackIsNeverCountedAsOrganizedCoverage() {
        String source = "一、工作要求\n各单位应组织宣传并总结本单位工作成效。";
        var initial = validator.validate("{\"suggestions\":[]}", DocumentImportTarget.PUBLIC_EVENT, source, "t");
        var out = new DocumentImportSectionsRecovery(mapper, validator).recover("{\"suggestions\":[]}", DocumentImportTarget.PUBLIC_EVENT, source, "t", initial);
        assertThat(checker.inspect(source, out.result(), out.mode()).state()).isEqualTo("RAW_REFERENCE_NOT_AI_ORGANIZATION");
        assertThat(out.result().warnings()).anyMatch(w -> w.message().contains("不代表 AI") && w.message().contains("联系人及电话") && w.message().contains("保存前"));
    }

    @Test void headingOnlyCitationCannotEstablishBodyCoverageAndUnstructuredSourceIsNotJudged() throws Exception {
        String source = "一、工作要求\n各单位应组织宣传并总结本单位工作成效。";
        assertThat(checker.inspect(source, body(source, "工作要求", "工作要求", DocumentImportTarget.ACTIVITY), "UNCHANGED").blocks())
                .allMatch(b -> b.status().equals("COVERAGE_NOT_ESTABLISHED"));
        assertThat(checker.inspect("普通通知文字", body("普通通知文字", "普通通知文字", "普通通知文字", DocumentImportTarget.ACTIVITY), "UNCHANGED").state())
                .isEqualTo("NO_RECOGNIZED_IMPORTANT_BLOCKS");
    }

    @Test void dedicatedTimeEvidenceDoesNotRequireDuplicateBodyButCannotMaskWorkingRequirements() throws Exception {
        String event = "学生组比赛安排在十月中旬举行，具体时间另行通知。";
        String duties = "各单位应组织宣传并总结本单位工作成效。";
        String source = "一、赛程安排\n" + event + "\n二、工作要求\n" + duties;
        var body = body(source, "正文待核对", "赛程安排", DocumentImportTarget.ACTIVITY);
        String raw = mapper.writeValueAsString(Map.of("suggestions", List.of(Map.of("target", "/timeline",
                "value", List.of(Map.of("type", "OTHER", "title", "学生组比赛", "schedule",
                        Map.of("kind", "TEXT", "timeDescription", "十月中旬，具体时间另行通知"))),
                "excerpts", List.of(event, duties)))));
        var time = validator.validate(raw, DocumentImportTarget.ACTIVITY, source, "t");
        assertThat(time.suggestions()).hasSize(1);
        var combined = new java.util.ArrayList<>(body.suggestions());
        combined.addAll(time.suggestions());
        var result = new DocumentImportSuggestionValidator.ValidationResult(combined, List.of());
        var assessment = checker.inspect(source, result, "UNCHANGED");
        assertThat(assessment.blocks()).anyMatch(b -> b.chapter().equals("赛程安排") && b.status().equals("OTHER_FIELD_EVIDENCE_REQUIRES_REVIEW"));
        assertThat(assessment.blocks()).anyMatch(b -> b.chapter().equals("工作要求") && b.status().equals("COVERAGE_NOT_ESTABLISHED"));
    }
}
