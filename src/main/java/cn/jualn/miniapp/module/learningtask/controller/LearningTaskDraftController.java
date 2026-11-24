package cn.jualn.miniapp.module.learningtask.controller;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.learningtask.LearningTaskEtags;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.AdminQuery;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Status;
import cn.jualn.miniapp.module.learningtask.converter.LearningTaskDraftConverter;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskDraftRequest;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskService;
import cn.jualn.miniapp.module.learningtask.vo.admin.LearningTaskAdminVO;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B2-A wire adapter; success requires current released grants and all applicable providers.
 */
@RestController
@RequestMapping(value = "/v1/admin/learning-tasks", produces = "application/json")
public class LearningTaskDraftController {
    private final LearningTaskService tasks;
    private final LearningTaskProviders providers;
    private final LearningTaskDraftConverter converter;

    public LearningTaskDraftController(LearningTaskService tasks, LearningTaskProviders providers, LearningTaskDraftConverter converter) {
        this.tasks = tasks;
        this.providers = providers;
        this.converter = converter;
    }

    /**
     * Before argument binding; does not replace the service's current resource/scope recheck.
     */
    @ModelAttribute
    public void authorizeEntry(HttpServletRequest request) {
        Action action = switch (request.getMethod()) {
            case "POST" -> Action.CREATE;
            case "PATCH" -> Action.EDIT;
            default -> Action.READ;
        };
        providers.authorize(action, null, null);
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<LearningTaskAdminVO.Detail> create(@RequestBody LearningTaskDraftRequest.Create request,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var result = tasks.create(converter.create(request), key).task();
        // Fixed mapping of the immutable result: replay preserves original 201, Location, ETag and body.
        return ResponseEntity.created(URI.create("/v1/admin/learning-tasks/" + result.taskId()))
                .cacheControl(CacheControl.noStore()).eTag(LearningTaskEtags.task(result))
                .body(converter.detail(result));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<LearningTaskAdminVO.Detail> get(@PathVariable String taskId) {
        var result = tasks.get(converter.taskId(taskId)).task();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(LearningTaskEtags.task(result)).body(converter.detail(result));
    }

    @PatchMapping(value = "/{taskId}", consumes = "application/json")
    public ResponseEntity<LearningTaskAdminVO.Detail> edit(@PathVariable String taskId,
                                                           @RequestBody LearningTaskDraftRequest.Edit request, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        var result = tasks.edit(converter.taskId(taskId), converter.edit(request), ifMatch).task();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(LearningTaskEtags.task(result)).body(converter.detail(result));
    }

    @GetMapping
    public ResponseEntity<LearningTaskAdminVO.Page> list(@RequestParam(defaultValue = "1") String page,
                                                         @RequestParam(defaultValue = "20") String pageSize, @RequestParam(required = false) String q,
                                                         @RequestParam(required = false) String status) {
        if (q != null && q.codePointCount(0, q.length()) > 200) {
            throw invalidQuery("q");
        }
        Status lifecycle = null;
        if (status != null) {
            try {
                lifecycle = Status.valueOf(status);
            } catch (IllegalArgumentException unknown) {
                throw invalidQuery("status");
            }
        }
        var result = tasks.listAdmin(new AdminQuery(integer(page, "page", Integer.MAX_VALUE), integer(pageSize, "pageSize", 100), q, lifecycle));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(converter.page(result));
    }

    private int integer(String value, String field, int max) {
        try {
            int number = Integer.parseInt(value);
            if (number < 1 || number > max) {
                throw invalidQuery(field);
            }
            return number;
        } catch (NumberFormatException invalid) {
            throw invalidQuery(field);
        }
    }

    private ContractProblemException invalidQuery(String field) {
        return ContractProblemException.validation(new ContractProblemException.Violation(
                "query", "#/" + field, "INVALID_VALUE", "Invalid LearningTask query value"));
    }
}
