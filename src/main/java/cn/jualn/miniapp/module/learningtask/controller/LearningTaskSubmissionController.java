package cn.jualn.miniapp.module.learningtask.controller;

import cn.jualn.miniapp.module.learningtask.LearningTaskProblems.Dependency;
import cn.jualn.miniapp.module.learningtask.LearningTaskEtags;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskSliceConverter;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskSubmissionRequest;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskService;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskSliceQueries;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskSubmissionQueries;
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

import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.unavailable;

@RestController
public class LearningTaskSubmissionController {
    private final LearningTaskService tasks;
    private final LearningTaskProviders providers;
    private final LearningTaskSliceQueries own;
    private final LearningTaskSubmissionQueries queries;
    private final LearningTaskDraftConverter ids;
    private final LearningTaskSliceConverter converter;

    public LearningTaskSubmissionController(LearningTaskService tasks, LearningTaskProviders providers, LearningTaskSliceQueries own,
                                            LearningTaskSubmissionQueries queries, LearningTaskDraftConverter ids, LearningTaskSliceConverter converter) {
        this.tasks = tasks;
        this.providers = providers;
        this.own = own;
        this.queries = queries;
        this.ids = ids;
        this.converter = converter;
    }

    @ModelAttribute
    public void authorizeEntry(HttpServletRequest request) {
        boolean admin = request.getRequestURI().startsWith(request.getContextPath() + "/v1/admin/");
        providers.authorize(admin ? ("POST".equals(request.getMethod()) ? LearningTaskBO.Action.REVIEW : LearningTaskBO.Action.REVIEW_READ)
                : ("POST".equals(request.getMethod()) ? LearningTaskBO.Action.SUBMIT : LearningTaskBO.Action.MY_READ), null, null);
    }

    @PostMapping("/v1/users/me/learning-task-assignments/{assignmentId}/submissions")
    public ResponseEntity<LearningTaskSliceVO.Submission> submit(@PathVariable String assignmentId, @RequestBody LearningTaskSubmissionRequest.Material body,
                                                                 @RequestHeader(value = "If-Match", required = false) String tag, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long assignment = ids.taskId(assignmentId), task = own.ownTask(assignment);
        var result = tasks.submit(task, assignment, new LearningTaskBO.Material(body.text(), body.fileIds().stream().map(ids::taskId).toList(), body.confirmed()), tag, key);
        return ResponseEntity.created(URI.create("/v1/users/me/learning-task-assignments/" + assignment + "/submissions/" + result.submission().submissionId()))
                .cacheControl(CacheControl.noStore()).eTag(LearningTaskEtags.submission(result.submission())).body(converter.submission(result.submission()));
    }

    @GetMapping("/v1/users/me/learning-task-assignments/{assignmentId}/submissions")
    public ResponseEntity<LearningTaskSliceVO.SubmissionPage> history(@PathVariable String assignmentId,
                                                                      @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.history(ids.taskId(assignmentId), number(page, Integer.MAX_VALUE), number(pageSize, 100)));
    }

    @GetMapping("/v1/users/me/learning-task-assignments/{assignmentId}/submissions/{submissionId}")
    public ResponseEntity<LearningTaskSliceVO.Submission> mine(@PathVariable String assignmentId, @PathVariable String submissionId) {
        long assignment = ids.taskId(assignmentId), task = own.ownTask(assignment);
        return submission(tasks.ownSubmission(task, assignment, ids.taskId(submissionId)));
    }

    @GetMapping("/v1/admin/learning-task-submissions")
    public ResponseEntity<LearningTaskSliceVO.ReviewPage> queue(@RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String pageSize,
                                                                @RequestParam(defaultValue = "true") String actionable, @RequestParam(required = false) String taskId, @RequestParam(required = false) String reviewStatus) {
        if (!"true".equals(actionable) && !"false".equals(actionable)) throw invalid("actionable");
        if (reviewStatus != null) try {
            LearningTaskBO.ReviewStatus.valueOf(reviewStatus);
        } catch (IllegalArgumentException failure) {
            throw invalid("reviewStatus");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.queue(number(page, Integer.MAX_VALUE), number(pageSize, 100),
                Boolean.parseBoolean(actionable), taskId == null ? null : ids.taskId(taskId), reviewStatus));
    }

    @GetMapping("/v1/admin/learning-task-submissions/{submissionId}")
    public ResponseEntity<LearningTaskSliceVO.ReviewDetail> detail(@PathVariable String submissionId) {
        var result = queries.detail(ids.taskId(submissionId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(LearningTaskEtags.review(result.facts(), result.view().submitterSummary())).body(result.view());
    }

    @PostMapping("/v1/admin/learning-task-submissions/{submissionId}:review")
    public ResponseEntity<LearningTaskSliceVO.Submission> review(@PathVariable String submissionId, @RequestBody LearningTaskSubmissionRequest.Review body,
                                                                 @RequestHeader(value = "If-Match", required = false) String tag, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var linked = tasks.adminSubmission(ids.taskId(submissionId));
        return submission(tasks.review(linked.task().taskId(), linked.assignment().assignmentId(), linked.submission().submissionId(),
                new LearningTaskBO.Review(body.decision(), body.reason()), tag, key));
    }

    private ResponseEntity<LearningTaskSliceVO.Submission> submission(LearningTaskBO.Result result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).eTag(LearningTaskEtags.submission(result.submission())).body(converter.submission(result.submission()));
    }

    private int number(String value, int maximum) {
        try {
            int n = Integer.parseInt(value);
            if (n < 1 || n > maximum) throw invalid("query");
            return n;
        } catch (NumberFormatException failure) {
            throw invalid("query");
        }
    }
}
