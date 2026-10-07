package cn.jualn.miniapp.module.exam.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.module.exam.converter.HomePublicMatterReminderConverter;
import cn.jualn.miniapp.module.exam.service.HomePublicMatterReminderService;
import cn.jualn.miniapp.module.exam.vo.HomePublicMatterRemindersVO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/home/public-matter-reminders")
@RequiredArgsConstructor
public class HomePublicMatterController {
    private final HomePublicMatterReminderService reminderService;
    private final HomePublicMatterReminderConverter converter;

    @GetMapping
    public ResponseEntity<HomePublicMatterRemindersVO> list() {
        long userId = StpUtil.getLoginIdAsLong();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(converter.toVO(reminderService.listForUser(userId)));
    }
}
