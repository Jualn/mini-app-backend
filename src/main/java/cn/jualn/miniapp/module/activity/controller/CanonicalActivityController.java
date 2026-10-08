package cn.jualn.miniapp.module.activity.controller;

import cn.jualn.miniapp.common.web.CursorPageVO;
import cn.jualn.miniapp.module.activity.converter.ActivityResourceConverter;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.vo.ActivitySummaryVO;
import cn.jualn.miniapp.module.activity.vo.ActivityDetailResourceVO;
import cn.jualn.miniapp.module.activity.vo.ActivitySubscriptionVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for the accepted discovery and subscription contract. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/activities")
public class CanonicalActivityController {
    private final ActivityService service;
    private final ActivityEnrollmentService subscriptionService;
    private final ActivityResourceConverter converter;

    @GetMapping
    public CursorPageVO<ActivitySummaryVO> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(name = "q", required = false) @Size(max = 200) String query,
            @RequestParam(defaultValue = "-publishedAt") String sort,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String lifecycleStatus,
            @RequestParam(required = false) String audienceFilter,
            @RequestParam(required = false) String departmentId) {
        var page = service.pageActivityResources(converter.query(cursor, pageSize, query, sort, category,
                lifecycleStatus, audienceFilter, departmentId));
        return new CursorPageVO<>(page.items().stream().map(converter::summary).toList(), page.nextCursor());
    }

    @GetMapping("/{activityId}")
    public ActivityDetailResourceVO detail(@PathVariable Long activityId) {
        return converter.detail(service.getActivityResource(activityId));
    }

    @GetMapping("/{activityId}/subscription")
    public ResponseEntity<ActivitySubscriptionVO> subscription(@PathVariable Long activityId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(converter.subscription(subscriptionService.getSubscriptionState(activityId)));
    }

    @PutMapping("/{activityId}/subscription")
    public ResponseEntity<ActivitySubscriptionVO> subscribe(@PathVariable Long activityId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(converter.subscription(subscriptionService.subscribeWithState(activityId)));
    }

    @DeleteMapping("/{activityId}/subscription")
    public ResponseEntity<Void> unsubscribe(@PathVariable Long activityId) {
        subscriptionService.unEnrollActivity(activityId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
