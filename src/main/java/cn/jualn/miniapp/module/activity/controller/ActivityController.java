package cn.jualn.miniapp.module.activity.controller;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.activity.bo.ActivityListBO;
import cn.jualn.miniapp.module.activity.bo.ActivityUploadBO;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.dto.request.ActivityCreateRequest;
import cn.jualn.miniapp.module.activity.dto.request.ActivityPageQuery;
import cn.jualn.miniapp.module.activity.dto.request.ActivityUpdateRequest;
import cn.jualn.miniapp.module.activity.service.ActivityAiService;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.activity.vo.ActivityDetailVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 活动接口。
 */
@RestController
@RequestMapping("/v1/activity")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;
    private final ActivityAiService activityAiService;
    private final ActivityConverter activityConverter;

    @PostMapping
    public Result<Long> createActivity(@RequestBody @Valid ActivityCreateRequest request) {
        return Result.ok(activityService.createActivity(activityConverter.toCreateBO(request)));
    }

    @PutMapping("/{id}")
    public Result<Void> updateActivity(@PathVariable Long id, @RequestBody @Valid ActivityUpdateRequest request) {
        request.setId(id);
        activityService.updateActivity(activityConverter.toUpdateBO(request));
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> removeActivity(@PathVariable Long id) {
        activityService.removeActivity(id);
        return Result.ok(null);
    }

    @GetMapping("/{id}") // TODO:直接返回了VO，后续优化一下
    public Result<ActivityDetailVO> getActivityDetail(@PathVariable Long id) {
        return Result.ok(activityService.getActivityDetail(id));
    }

    @GetMapping
    public Result<PageResult<ActivityListBO>> pageActivity(@Valid ActivityPageQuery query) {
        return Result.ok(activityService.pageActivityList(activityConverter.toPageBO(query)));
    }

    @PostMapping("/ai-extract/upload")
    public Result<ActivityUploadBO> upload(
            @RequestPart MultipartFile file) {

        return Result.ok(activityAiService.upload(file));
    }

    @GetMapping(
            value = "/ai-extract/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter stream(
            @RequestParam String taskId,
            @RequestParam(defaultValue = "{}") String skipped) {
        // long userId = StpUtil.getLoginIdAsLong(); // 主线程取，安全
        return activityAiService.stream(taskId, skipped);
    }
}