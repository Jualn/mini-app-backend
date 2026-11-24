package cn.jualn.miniapp.module.learningtask.controller;

import cn.jualn.miniapp.module.learningtask.service.LearningTaskFiles;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskFileRequest.*;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;

import java.net.URI;
import java.util.List;

import cn.jualn.miniapp.module.learningtask.vo.admin.LearningTaskCounterSerializer;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class LearningTaskController {
    private final LearningTaskFiles files;
    private final LearningTaskDraftConverter ids;

    @ModelAttribute
    public void entry(HttpServletRequest request) {
        String uri = request.getRequestURI();
        files.entry(uri.startsWith(request.getContextPath() + "/v1/admin/"), uri.endsWith("learning-task-file-capabilities"), "POST".equals(request.getMethod()));
    }

    public record Credentials(String bucket, String region, List<String> objectKeys, String tmpSecretId,
                              String tmpSecretKey, String sessionToken,
                              @JsonSerialize(using = LearningTaskCounterSerializer.class) long expiredTime,
                              String customDomain, String uploadId) {
        @Override
        public String toString() {
            return "LearningTaskFileCredentials[redacted]";
        }
    }

    @GetMapping("/v1/admin/learning-task-file-capabilities")
    public ResponseEntity<?> capabilities(@RequestParam(required = false) String taskId, @RequestParam(required = false) String targetType, @RequestParam(required = false) String organizationId) {
        return ok(files.capabilities(taskId == null ? null : ids.taskId(taskId), targetType, organizationId));
    }

    @PostMapping("/v1/users/me/learning-task-assignments/{assignmentId}/file-upload-credentials")
    public ResponseEntity<?> upload(@PathVariable String assignmentId, @RequestBody Upload body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var c = files.upload(ids.taskId(assignmentId), body.files(), key);
        return ok(new Credentials(c.bucket(), c.region(), c.objectKeys(), c.tmpSecretId(), c.tmpSecretKey(), c.sessionToken(), c.expiredTime(), c.customDomain(), c.uploadId()));
    }

    @PostMapping("/v1/users/me/learning-task-assignments/{assignmentId}/files")
    public ResponseEntity<?> register(@PathVariable String assignmentId, @RequestBody Register body, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = files.register(ids.taskId(assignmentId), body.uploadId(), body.fileIndex(), key);
        return ResponseEntity.created(URI.create("/v1/users/me/learning-task-assignments/" + assignmentId + "/files/" + result.fileId())).cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/v1/users/me/learning-task-assignments/{assignmentId}/files/{fileId}")
    public ResponseEntity<?> metadata(@PathVariable String assignmentId, @PathVariable String fileId) {
        return ok(files.metadata(ids.taskId(assignmentId), ids.taskId(fileId)));
    }

    @GetMapping("/v1/users/me/learning-task-assignments/{assignmentId}/files/{fileId}/access")
    public ResponseEntity<?> access(@PathVariable String assignmentId, @PathVariable String fileId) {
        return ok(files.access(ids.taskId(assignmentId), ids.taskId(fileId), false));
    }

    @GetMapping("/v1/admin/learning-task-files/{fileId}/access")
    public ResponseEntity<?> adminAccess(@PathVariable String fileId) {
        return ok(files.access(null, ids.taskId(fileId), true));
    }

    private ResponseEntity<?> ok(Object value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
