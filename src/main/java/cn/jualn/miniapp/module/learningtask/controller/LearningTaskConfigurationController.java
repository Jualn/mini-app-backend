package cn.jualn.miniapp.module.learningtask.controller;

import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskConfigurationQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class LearningTaskConfigurationController {
    private final LearningTaskConfigurationQueries queries;
    private final LearningTaskDraftConverter ids;

    @GetMapping("/v1/admin/learning-tasks/{taskId}/configuration-summary")
    public ResponseEntity<LearningTaskConfigurationQueries.Summary> get(@PathVariable String taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.get(ids.taskId(taskId)));
    }
}
