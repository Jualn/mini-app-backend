package cn.jualn.miniapp.module.notify.controller;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.notify.dto.request.CanonicalNotificationQuery;
import cn.jualn.miniapp.module.notify.dto.request.MarkNotificationReadRequest;
import cn.jualn.miniapp.module.notify.dto.request.UpdateNotificationPreferencesRequest;
import cn.jualn.miniapp.module.notify.service.CanonicalNotificationService;
import cn.jualn.miniapp.module.notify.vo.NotificationChannelCapabilitiesVO;
import cn.jualn.miniapp.module.notify.vo.NotificationPreferencesVO;
import cn.jualn.miniapp.module.notify.vo.NotificationUnreadCountVO;
import cn.jualn.miniapp.module.notify.service.NotificationCenterService;
import cn.jualn.miniapp.module.notify.converter.NotificationCenterConverter;
import cn.jualn.miniapp.module.notify.dto.request.BatchReadNotificationsRequest;
import cn.jualn.miniapp.module.notify.dto.request.ReadNotificationsThroughRequest;
import cn.jualn.miniapp.module.notify.vo.NotificationItemVO;
import cn.jualn.miniapp.module.notify.vo.NotificationSummaryVO;
import cn.jualn.miniapp.module.notify.vo.NotificationReadResultVO;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/users/me")
@RequiredArgsConstructor
public class CanonicalNotificationController {
    private final CanonicalNotificationService notificationService;
    private final NotificationCenterService centerService;
    private final NotificationCenterConverter centerConverter;

    @GetMapping("/notification-preferences")
    public ResponseEntity<NotificationPreferencesVO> getPreferences() {
        return noStore(notificationService.getPreferences());
    }

    @PatchMapping("/notification-preferences")
    public ResponseEntity<NotificationPreferencesVO> updatePreferences(
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return noStore(notificationService.updatePreferences(request));
    }

    @PostMapping("/notification-preferences:batch-update")
    public ResponseEntity<NotificationPreferencesVO> batchUpdatePreferences(
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return noStore(notificationService.updatePreferences(request));
    }

    @GetMapping("/notification-channel-capabilities")
    public ResponseEntity<NotificationChannelCapabilitiesVO> getCapabilities() {
        return noStore(notificationService.getCapabilities());
    }

    @GetMapping("/notifications")
    public ResponseEntity<?> list(@Valid CanonicalNotificationQuery query) {
        if (query.getCategory() != null && query.getBoxCategory() != null
                || query.getBoxCategory() != null && !"structured".equals(query.getRepresentation())) {
            throw ContractProblemException.validation(new ContractProblemException.Violation(
                    "query", "boxCategory", "INVALID", "boxCategory 仅用于 structured 且不能与 category 同时使用"));
        }
        if ("structured".equals(query.getRepresentation())) {
            return noStore(centerConverter.page(centerService.list(centerConverter.query(query))));
        }
        return noStore(notificationService.list(query));
    }

    @GetMapping("/notifications/summary")
    public ResponseEntity<NotificationSummaryVO> summary(@RequestParam(required = false) String afterCursor) {
        return noStore(centerConverter.summary(centerService.summary(afterCursor)));
    }

    @GetMapping("/notifications/{notificationId}")
    public ResponseEntity<NotificationItemVO> get(@PathVariable @NotBlank @Size(max = 128) String notificationId) {
        return noStore(centerConverter.item(centerService.get(notificationId)));
    }

    @PostMapping("/notifications:batch-read")
    public ResponseEntity<NotificationReadResultVO> batchRead(@Valid @RequestBody BatchReadNotificationsRequest request) {
        rejectUnknown(request.getUnknownProperties());
        return noStore(centerConverter.read(centerService.batchRead(request.getNotificationIds())));
    }

    @PostMapping("/notifications:mark-read-through")
    public ResponseEntity<NotificationReadResultVO> readThrough(@Valid @RequestBody ReadNotificationsThroughRequest request) {
        rejectUnknown(request.getUnknownProperties());
        return noStore(centerConverter.read(centerService.readThrough(request.getThroughCursor())));
    }

    private void rejectUnknown(java.util.Map<String, Object> fields) {
        if (!fields.isEmpty()) {
            throw ContractProblemException.validation(new ContractProblemException.Violation(
                    "body", "/" + fields.keySet().iterator().next(), "UNKNOWN_PROPERTY", "不支持的字段"));
        }
    }

    @GetMapping("/notifications/unread-count")
    public ResponseEntity<NotificationUnreadCountVO> unreadCount() {
        return noStore(new NotificationUnreadCountVO(notificationService.unreadCount()));
    }

    @PutMapping("/notifications/{notificationId}/read-state")
    public ResponseEntity<Void> markRead(
            @PathVariable @NotBlank @Size(max = 128) String notificationId,
            @Valid @RequestBody MarkNotificationReadRequest request) {
        rejectUnknownReadFields(request);
        notificationService.markRead(notificationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/notifications:mark-all-read")
    public ResponseEntity<Void> markAllRead() {
        notificationService.markAllRead();
        return ResponseEntity.noContent().build();
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue())
                .body(body);
    }

    private void rejectUnknownReadFields(MarkNotificationReadRequest request) {
        if (!request.getUnknownProperties().isEmpty()) {
            String name = request.getUnknownProperties().keySet().iterator().next();
            throw ContractProblemException.validation(new ContractProblemException.Violation(
                    "body", "/" + name, "UNKNOWN_PROPERTY", "不支持的字段"));
        }
    }
}
