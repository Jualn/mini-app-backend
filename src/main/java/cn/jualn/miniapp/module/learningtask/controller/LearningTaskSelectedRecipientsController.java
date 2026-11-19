package cn.jualn.miniapp.module.learningtask.controller;

import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskSelectedRecipients;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class LearningTaskSelectedRecipientsController {
    private final LearningTaskSelectedRecipients recipients;
    private final LearningTaskDraftConverter ids;

    @GetMapping("/v1/admin/learning-tasks/{taskId}/selected-recipients")
    public ResponseEntity<LearningTaskSelectedRecipients.Page> list(@PathVariable String taskId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(recipients.list(ids.taskId(taskId), cursor, pageSize));
    }
}
