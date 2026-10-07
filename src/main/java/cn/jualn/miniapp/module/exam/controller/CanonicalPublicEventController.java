package cn.jualn.miniapp.module.exam.controller;

import cn.jualn.miniapp.common.web.CursorPageVO;
import cn.jualn.miniapp.module.exam.converter.PublicEventResourceConverter;
import cn.jualn.miniapp.module.exam.service.ExamService;
import cn.jualn.miniapp.module.exam.service.ExamSubscriptionService;
import cn.jualn.miniapp.module.exam.vo.PublicEventSummaryVO;
import cn.jualn.miniapp.module.exam.vo.PublicEventDetailVO;
import cn.jualn.miniapp.module.exam.vo.PublicEventSubscriptionVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
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
@Validated
@RequiredArgsConstructor
@RequestMapping("/v1/public-events")
public class CanonicalPublicEventController {
    private final ExamService service;
    private final ExamSubscriptionService subscriptionService;
    private final PublicEventResourceConverter converter;

    @GetMapping
    public CursorPageVO<PublicEventSummaryVO> list(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(name = "q", required = false) @Size(min = 1, max = 200) String query,
            @RequestParam(defaultValue = "-publishedAt") String sort,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String lifecycleStatus) {
        var page = service.pagePublicEventResources(converter.query(cursor, pageSize, query, sort, type, lifecycleStatus));
        return new CursorPageVO<>(page.items().stream().map(converter::summary).toList(), page.nextCursor());
    }

    @GetMapping("/{publicEventId}")
    public PublicEventDetailVO detail(@PathVariable Long publicEventId) {
        return converter.detail(service.getPublicEventResource(publicEventId));
    }

    @GetMapping("/{publicEventId}/subscription")
    public ResponseEntity<PublicEventSubscriptionVO> subscription(@PathVariable Long publicEventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(converter.subscription(subscriptionService.getSubscriptionState(publicEventId)));
    }

    @PutMapping("/{publicEventId}/subscription")
    public ResponseEntity<PublicEventSubscriptionVO> subscribe(@PathVariable Long publicEventId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(converter.subscription(subscriptionService.subscribeWithState(publicEventId)));
    }

    @DeleteMapping("/{publicEventId}/subscription")
    public ResponseEntity<Void> unsubscribe(@PathVariable Long publicEventId) {
        subscriptionService.unsubscribeExam(publicEventId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
