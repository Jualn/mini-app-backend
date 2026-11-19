package cn.jualn.miniapp.module.learningtask.controller;

import cn.jualn.miniapp.module.learningtask.service.LearningTaskRecipientOptions;
import cn.jualn.miniapp.module.article.converter.ArticleConverter;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;

@RestController
@RequiredArgsConstructor
public class LearningTaskOptionsController {
    private final LearningTaskRecipientOptions recipients;

    @GetMapping("/v1/admin/learning-task-recipient-options")
    public ResponseEntity<LearningTaskRecipientOptions.Page> recipients(@RequestParam(required = false) String taskId,
                                                                        @RequestParam(required = false) String targetType, @RequestParam(required = false) String organizationId,
                                                                        @RequestParam String type, @RequestParam(required = false) String q, @RequestParam(required = false) String organizationIds,
                                                                        @RequestParam(required = false) String developmentStageIds, @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recipients.list(ArticleConverter.optionalLookupId(taskId), targetType, organizationId, type, q, ids(organizationIds), ids(developmentStageIds), cursor, pageSize));
    }

    private List<String> ids(String value) {
        return value == null ? List.of() : List.of(value.split(",", -1));
    }
}
