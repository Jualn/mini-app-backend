package cn.jualn.miniapp.module.learningtask.controller;

import cn.jualn.miniapp.module.learningtask.service.LearningTaskRecipientOptions;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid;

@RestController
@RequiredArgsConstructor
public class LearningTaskOptionsController {
    private final LearningTaskRecipientOptions recipients;

    @GetMapping("/v1/admin/learning-task-recipient-options")
    public ResponseEntity<LearningTaskRecipientOptions.Page> recipients(@RequestParam(required = false) String taskId,
                                                                        @RequestParam(required = false) String targetType, @RequestParam(required = false) String organizationId,
                                                                        @RequestParam String type, @RequestParam(required = false) String q, @RequestParam(required = false) String organizationIds,
                                                                        @RequestParam(required = false) String developmentStageIds, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int pageSize,
                                                                        @RequestParam(defaultValue = "false") boolean selectableOnly,
                                                                        @RequestParam(required = false) String excludePersonIds,
                                                                        @RequestParam(defaultValue = "false") boolean completeOrganization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recipients.list(ArticleConverter.optionalLookupId(taskId), targetType, organizationId, type, q, ids(organizationIds), ids(developmentStageIds), cursor, pageSize, selectableOnly, ids(excludePersonIds), completeOrganization));
    }

    public record Query(String taskId, String targetType, String organizationId, String type, String q,
                        List<String> organizationIds, List<String> developmentStageIds, String cursor, Integer pageSize,
                        Boolean selectableOnly, List<String> excludePersonIds, Boolean completeOrganization) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw invalid(name);
        }
    }

    @PostMapping("/v1/admin/learning-task-recipient-options")
    public ResponseEntity<LearningTaskRecipientOptions.Page> search(@RequestBody Query query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recipients.list(
                ArticleConverter.optionalLookupId(query.taskId()), query.targetType(), query.organizationId(), query.type(), query.q(),
                query.organizationIds() == null ? List.of() : query.organizationIds(),
                query.developmentStageIds() == null ? List.of() : query.developmentStageIds(), query.cursor(),
                query.pageSize() == null ? 20 : query.pageSize(), Boolean.TRUE.equals(query.selectableOnly()),
                query.excludePersonIds() == null ? List.of() : query.excludePersonIds(), Boolean.TRUE.equals(query.completeOrganization())));
    }

    private List<String> ids(String value) {
        return value == null ? List.of() : List.of(value.split(",", -1));
    }
}
