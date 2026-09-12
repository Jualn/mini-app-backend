package cn.jualn.miniapp.module.activity.controller;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.activity.service.ActivityRegistrationService;
import cn.jualn.miniapp.module.activity.converter.ActivityRegistrationConverter;
import cn.jualn.miniapp.module.activity.dto.request.ActivityRegistrationRequest;
import cn.jualn.miniapp.module.activity.vo.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/v1/activity/{activityId}")
public class ActivityRegistrationController {
    private final ActivityRegistrationService service;
    private final ActivityRegistrationConverter converter;
    @GetMapping("/form")
    public Result<ActivityFormVO> form(@PathVariable @Positive Long activityId) {
        return Result.ok(converter.toFormVO(service.getForm(activityId)));
    }
    @GetMapping("/registration")
    public Result<ActivityRegistrationVO> mine(@PathVariable @Positive Long activityId) {
        return Result.ok(converter.toVO(service.getMine(activityId)));
    }
    @PostMapping("/registration")
    public Result<ActivityRegistrationVO> submit(@PathVariable @Positive Long activityId, @RequestBody @Valid ActivityRegistrationRequest request) {
        return Result.ok(converter.toVO(service.submit(activityId, request.formData())));
    }
    @DeleteMapping("/registration")
    public Result<Void> cancel(@PathVariable @Positive Long activityId) {
        service.cancelMine(activityId); return Result.ok(null);
    }
}
