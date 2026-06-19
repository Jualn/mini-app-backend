package cn.jualn.miniapp.module.notify.controller;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.notify.bo.NotificationBO;
import cn.jualn.miniapp.module.notify.bo.NotificationPageBO;
import cn.jualn.miniapp.module.notify.dto.request.NotificationPageQuery;
import cn.jualn.miniapp.module.notify.converter.NotifyConverter;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.notify.vo.NotificationVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/v1/notify")
@RequiredArgsConstructor
public class NotifyController {

	private final NotifyService notifyService;
	private final NotifyConverter notifyConverter;

	@GetMapping("/me")
	public Result<PageResult<NotificationVO>> pageCurrentUserNotifications(@Valid NotificationPageQuery query) {
		NotificationPageBO pageBO = notifyConverter.toPageBO(query);
		PageResult<NotificationBO> pageResult = notifyService.pageCurrentUserNotifications(pageBO);
		return Result.ok(PageResult.of(
				notifyConverter.toVOList(pageResult.getList()),
				pageResult.getHasMore(),
				pageResult.getNextCursor()
		));
	}

	@GetMapping("/me/unread-count")
	public Result<Long> countCurrentUnreadNotifications() {
		return Result.ok(notifyService.countCurrentUnreadNotifications());
	}

	@PutMapping("/{id}/read")
	public Result<Void> markAsRead(@PathVariable @NotNull Long id) {
		notifyService.markAsRead(id);
		return Result.ok(null);
	}

	@PutMapping("/me/read-all")
	public Result<Void> markAllAsRead() {
		notifyService.markAllAsRead();
		return Result.ok(null);
	}
}
