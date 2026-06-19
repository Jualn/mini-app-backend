package cn.jualn.miniapp.module.setting.controller;

import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.setting.converter.SettingConverter;
import cn.jualn.miniapp.module.setting.dto.request.UserSettingUpdateRequest;
import cn.jualn.miniapp.module.setting.service.SettingService;
import cn.jualn.miniapp.module.setting.vo.UserSettingVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/setting")
public class SettingController {

    private final SettingService settingService;
    private final SettingConverter settingConverter;

    @GetMapping
    public Result<UserSettingVO> getCurrentSetting() {
        return Result.ok(
                settingConverter.toVO(settingService.getCurrentSetting())
        );
    }

    @PutMapping
    public Result<String> updateCurrentSetting(@Valid @RequestBody UserSettingUpdateRequest req) {
        settingService.updateCurrentSetting(settingConverter.toBO(req));
        return Result.ok(null);
    }

}
