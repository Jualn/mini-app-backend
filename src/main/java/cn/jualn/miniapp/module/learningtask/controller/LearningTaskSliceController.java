package cn.jualn.miniapp.module.learningtask.controller;

import cn.jualn.miniapp.module.learningtask.LearningTaskEtags;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskSliceConverter;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskSliceRequest;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskService;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskSliceQueries;
import cn.jualn.miniapp.module.learningtask.vo.admin.LearningTaskAdminVO;
import cn.jualn.miniapp.module.learningtask.vo.admin.LearningTaskSliceVO;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LearningTaskSliceController {
    private final LearningTaskService tasks;
    private final LearningTaskSliceQueries queries;
    private final LearningTaskProviders providers;
    private final LearningTaskDraftConverter drafts;
    private final LearningTaskSliceConverter converter;

    public LearningTaskSliceController(LearningTaskService tasks, LearningTaskSliceQueries queries, LearningTaskProviders providers,
                                       LearningTaskDraftConverter drafts, LearningTaskSliceConverter converter) {
        this.tasks = tasks;
        this.queries = queries;
        this.providers = providers;
        this.drafts = drafts;
        this.converter = converter;
    }

    @ModelAttribute
    public void authorizeEntry(HttpServletRequest request) {
        String path = request.getRequestURI();
        LearningTaskBO.Action action;
        if (!path.contains("/v1/admin/")) {
            action = path.endsWith(":confirm") ? LearningTaskBO.Action.CONFIRM : LearningTaskBO.Action.MY_READ;
        } else if (path.contains("/audience-previews")) {
            action = LearningTaskBO.Action.PREVIEW;
        } else if (path.endsWith(":publish")) {
            action = LearningTaskBO.Action.PUBLISH;
        } else if (path.endsWith(":adjust-deadline")) {
            action = LearningTaskBO.Action.DEADLINE;
        } else if (path.endsWith(":cancel")) {
            action = LearningTaskBO.Action.CANCEL;
        } else if (path.endsWith(":close")) {
            action = LearningTaskBO.Action.CLOSE;
        } else if (path.endsWith(":revoke")) {
            action = LearningTaskBO.Action.REVOKE;
        } else if (path.endsWith("/assignments") && "POST".equals(request.getMethod())) {
            action = LearningTaskBO.Action.ADD;
        } else {
            action = LearningTaskBO.Action.READ;
        }
        providers.authorize(action, null, null);
    }

    @PostMapping("/v1/admin/learning-tasks/{taskId}/audience-previews")
    public ResponseEntity<LearningTaskSliceVO.Preview> preview(@PathVariable String taskId, @RequestBody LearningTaskSliceRequest.Preview request,
                                                               @RequestHeader(value = "If-Match", required = false) String tag, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = tasks.preview(drafts.taskId(taskId), new LearningTaskBO.PreviewRequest(request.purpose(), drafts.audience(request.audience())), tag, key).preview();
        return ResponseEntity.created(URI.create("/v1/admin/learning-tasks/" + taskId + "/audience-previews/" + result.previewId()))
                .cacheControl(CacheControl.noStore()).eTag(LearningTaskEtags.preview(result)).body(converter.preview(result));
    }

    @PostMapping("/v1/admin/learning-tasks/{taskId}:publish")
    public ResponseEntity<LearningTaskAdminVO.Detail> publish(@PathVariable String taskId, @RequestBody LearningTaskSliceRequest.Apply request,
                                                              @RequestHeader(value = "If-Match", required = false) String tag, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = tasks.apply(drafts.taskId(taskId), new LearningTaskBO.Apply(drafts.taskId(request.previewId()), request.reason()), LearningTaskBO.Purpose.PUBLISH, tag, key).task();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(LearningTaskEtags.task(result)).body(drafts.detail(result));
    }

    @GetMapping("/v1/users/me/learning-tasks")
    public ResponseEntity<LearningTaskSliceVO.MyPage> mine(@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") String pageSize,
                                                           @RequestParam(required = false) String progress) {
        checkProgress(progress);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.mine(cursor, number(pageSize, 50), progress));
    }

    @GetMapping("/v1/users/me/learning-task-assignments/{assignmentId}")
    public ResponseEntity<LearningTaskSliceVO.MyDetail> detail(@PathVariable String assignmentId) {
        long assignment = drafts.taskId(assignmentId), task = queries.ownTask(assignment);
        return mineResponse(tasks.mine(task, assignment));
    }

    @PostMapping("/v1/users/me/learning-task-assignments/{assignmentId}:confirm")
    public ResponseEntity<LearningTaskSliceVO.MyDetail> confirm(@PathVariable String assignmentId, @RequestBody LearningTaskSliceRequest.Confirm request,
                                                                @RequestHeader(value = "If-Match", required = false) String tag, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long assignment = drafts.taskId(assignmentId), task = queries.ownTask(assignment);
        return mineResponse(tasks.confirm(task, assignment, tag, key));
    }

    @GetMapping("/v1/admin/learning-tasks/{taskId}/assignments")
    public ResponseEntity<LearningTaskSliceVO.AssignmentPage> assignments(@PathVariable String taskId,
                                                                          @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize,
                                                                          @RequestParam(required = false) String allocationStatus, @RequestParam(required = false) String progress, @RequestParam(required = false) String userId) {
        checkProgress(progress);
        if (allocationStatus != null && !java.util.Set.of("ACTIVE", "REVOKED").contains(allocationStatus)) {
            throw invalid("allocationStatus");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.assignments(drafts.taskId(taskId), number(page, Integer.MAX_VALUE),
                number(pageSize, 100), allocationStatus, progress, userId == null ? null : drafts.taskId(userId)));
    }

    @GetMapping("/v1/admin/learning-tasks/{taskId}/assignments/{assignmentId}")
    public ResponseEntity<LearningTaskSliceVO.Assignment> assignment(@PathVariable String taskId, @PathVariable String assignmentId) {
        var result = tasks.adminAssignment(drafts.taskId(taskId), drafts.taskId(assignmentId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(LearningTaskEtags.assignment(result.task(), result.assignment()))
                .body(converter.assignment(result.assignment()));
    }

    @GetMapping("/v1/admin/learning-tasks/{taskId}/statistics")
    public ResponseEntity<LearningTaskSliceVO.Statistics> statistics(@PathVariable String taskId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.statistics(drafts.taskId(taskId)));
    }

    @GetMapping("/v1/admin/learning-tasks/{taskId}/audience-previews/{previewId}")
    public ResponseEntity<LearningTaskSliceVO.Preview> preview(@PathVariable String taskId, @PathVariable String previewId) {
        var result = tasks.preview(drafts.taskId(taskId), drafts.taskId(previewId)).preview();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(LearningTaskEtags.preview(result)).body(converter.preview(result));
    }

    @GetMapping("/v1/admin/learning-tasks/{taskId}/audience-previews/{previewId}/members")
    public ResponseEntity<LearningTaskSliceVO.PreviewMemberPage> members(@PathVariable String taskId, @PathVariable String previewId,
                                                                         @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize) {
        var result = tasks.previewMembers(drafts.taskId(taskId), drafts.taskId(previewId), number(page, Integer.MAX_VALUE), number(pageSize, 100));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LearningTaskSliceVO.PreviewMemberPage(
                result.items().stream().map(member -> new LearningTaskSliceVO.AudienceMember(member.subject(), member.availability(), member.deliveryUserId() == null ? null : member.deliveryUserId().toString(), member.bindingId(), member.alreadyAssigned())).toList(), result.page(), result.pageSize(), result.totalItems()));
    }

    @PostMapping("/v1/admin/learning-tasks/{taskId}/assignments")
    public ResponseEntity<LearningTaskAdminVO.Detail> add(@PathVariable String taskId, @RequestBody LearningTaskSliceRequest.Apply request,
                                                          @RequestHeader(value = "If-Match", required = false) String tag, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return taskResponse(tasks.apply(drafts.taskId(taskId), new LearningTaskBO.Apply(drafts.taskId(request.previewId()), request.reason()), LearningTaskBO.Purpose.ADD, tag, key));
    }

    @PostMapping("/v1/admin/learning-tasks/{taskId}:adjust-deadline")
    public ResponseEntity<LearningTaskAdminVO.Detail> deadline(@PathVariable String taskId, @RequestBody LearningTaskSliceRequest.Lifecycle request,
                                                               @RequestHeader(value = "If-Match", required = false) String tag, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (request.firstDeadline() == null || request.participationPresent()) {
            throw cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid("firstDeadline");
        }
        return taskResponse(tasks.deadline(drafts.taskId(taskId), new LearningTaskBO.Deadline(request.firstDeadline().atZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime(), request.reason()), tag, key));
    }

    @PostMapping("/v1/admin/learning-tasks/{taskId}:cancel")
    public ResponseEntity<LearningTaskAdminVO.Detail> cancel(@PathVariable String taskId, @RequestBody LearningTaskSliceRequest.Lifecycle request,
                                                             @RequestHeader(value = "If-Match", required = false) String tag, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (request.firstDeadline() != null || request.participationPresent()) {
            throw cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid("body");
        }
        return taskResponse(tasks.cancel(drafts.taskId(taskId), request.reason(), tag, key));
    }

    @PostMapping("/v1/admin/learning-tasks/{taskId}:close")
    public ResponseEntity<LearningTaskAdminVO.Detail> close(@PathVariable String taskId,
                                                            @RequestHeader(value = "If-Match", required = false) String tag, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return taskResponse(tasks.close(drafts.taskId(taskId), tag, key));
    }

    @PostMapping("/v1/admin/learning-tasks/{taskId}/assignments/{assignmentId}:revoke")
    public ResponseEntity<LearningTaskSliceVO.Assignment> revoke(@PathVariable String taskId, @PathVariable String assignmentId, @RequestBody LearningTaskSliceRequest.Lifecycle request,
                                                                 @RequestHeader(value = "If-Match", required = false) String tag, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (request.firstDeadline() != null) {
            throw cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid("body");
        }
        var result = tasks.revoke(drafts.taskId(taskId), drafts.taskId(assignmentId), new LearningTaskBO.Revoke(request.reason(), request.allowParticipated()), tag, key);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(LearningTaskEtags.assignment(result.task(), result.assignment())).body(converter.assignment(result.assignment()));
    }

    private ResponseEntity<LearningTaskAdminVO.Detail> taskResponse(LearningTaskBO.Result result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(LearningTaskEtags.task(result.task())).body(drafts.detail(result.task()));
    }

    private ResponseEntity<LearningTaskSliceVO.MyDetail> mineResponse(LearningTaskBO.Result result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(LearningTaskEtags.assignment(result.task(), result.assignment()))
                .body(converter.mine(result));
    }

    private int number(String value, int maximum) {
        try {
            int number = Integer.parseInt(value);
            if (number < 1 || number > maximum) {
                throw invalid("query");
            }
            return number;
        } catch (NumberFormatException failure) {
            throw invalid("query");
        }
    }

    private void checkProgress(String value) {
        if (value != null) {
            try {
                LearningTaskBO.Progress.valueOf(value);
            } catch (IllegalArgumentException failure) {
                throw invalid("progress");
            }
        }
    }

    private cn.jualn.miniapp.common.exception.ContractProblemException invalid(String field) {
        return cn.jualn.miniapp.common.exception.ContractProblemException.validation(new cn.jualn.miniapp.common.exception.ContractProblemException.Violation("query", "#/" + field, "INVALID_VALUE", "Invalid query value"));
    }
}
