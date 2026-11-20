package cn.jualn.miniapp.module.notify.controller;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.notify.dto.request.CanonicalNotificationQuery;
import cn.jualn.miniapp.module.notify.dto.request.MarkNotificationReadRequest;
import cn.jualn.miniapp.module.notify.dto.request.UpdateNotificationPreferencesRequest;
import cn.jualn.miniapp.module.notify.service.inbox.PersonalNotificationInbox;
import cn.jualn.miniapp.module.notify.service.preference.NotificationPreferences;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCapabilityQueries;
import cn.jualn.miniapp.module.notify.converter.NotificationPersonalConverter;
import cn.jualn.miniapp.module.notify.vo.NotificationChannelCapabilitiesVO;
import cn.jualn.miniapp.module.notify.vo.NotificationPreferencesVO;
import cn.jualn.miniapp.module.notify.vo.NotificationUnreadCountVO;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationCenterService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final PersonalNotificationInbox notificationService;
    private final NotificationPreferences preferences;
    private final NotificationCapabilityQueries capabilities;
    private final NotificationPersonalConverter personalConverter;
    private final NotificationCenterService centerService;
    private final NotificationCenterConverter centerConverter;
    private final cn.jualn.miniapp.module.notify.service.capability.NotificationCatalogQueries catalog;
    private final cn.jualn.miniapp.module.notify.converter.NotificationCatalogConverter catalogConverter;

    @GetMapping("/notification-preferences")
    public ResponseEntity<NotificationPreferencesVO> getPreferences() {
        return noStore(personalConverter.preferences(preferences.getPreferences()));
    }

    @PatchMapping("/notification-preferences")
    public ResponseEntity<NotificationPreferencesVO> updatePreferences(
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return noStore(personalConverter.preferences(preferences.updatePreferences(personalConverter.patch(request))));
    }

    @PostMapping("/notification-preferences:batch-update")
    public ResponseEntity<NotificationPreferencesVO> batchUpdatePreferences(
            @Valid @RequestBody UpdateNotificationPreferencesRequest request) {
        return noStore(personalConverter.preferences(preferences.updatePreferences(personalConverter.patch(request))));
    }

    @GetMapping("/notification-channel-capabilities")
    public ResponseEntity<NotificationChannelCapabilitiesVO> getCapabilities() {
        return noStore(personalConverter.capabilities(capabilities.getCapabilities()));
    }

    @GetMapping("/notification-subscription-options")
    public ResponseEntity<cn.jualn.miniapp.module.notify.vo.NotificationCatalogVO.Options> subscriptionOptions(
            @RequestParam(required=false) cn.jualn.miniapp.module.notify.model.NotificationCategory category,
            @RequestParam java.util.Map<String,String> parameters) {
        validateParameters(parameters,java.util.Set.of("category"));
        return noStore(catalogConverter.options(catalog.options(category)));
    }

    @GetMapping("/notifications")
    public ResponseEntity<cn.jualn.miniapp.module.notify.vo.NotificationListVO> list(@Valid CanonicalNotificationQuery query,
            @RequestParam java.util.Map<String,String> parameters) {
        validateParameters(parameters,java.util.Set.of("category","boxCategory","cursor","pageSize","isRead"));
        if(query.getCategory()!=null&&query.getBoxCategory()!=null)throw ContractProblemException.validation(
                new ContractProblemException.Violation("query","boxCategory","INVALID","不能同时提交两种类别"));
        return noStore(centerConverter.page(centerService.list(centerConverter.query(query))));
    }

    public static void validateParameters(java.util.Map<String,String> parameters,java.util.Set<String> allowed) {
        for(String key:parameters.keySet())if(!allowed.contains(key))throw ContractProblemException.validation(
                new ContractProblemException.Violation("query",key,"UNKNOWN_PROPERTY","不支持的参数"));
    }

    @GetMapping("/notifications/summary")
    public ResponseEntity<NotificationSummaryVO> summary(@RequestParam(required = false) String afterCursor) {
        return noStore(centerConverter.summary(centerService.summary(afterCursor)));
    }

    @DeleteMapping("/notifications/{notificationId}")
    public ResponseEntity<Void> delete(@PathVariable @NotBlank @Size(max = 128) String notificationId) {
        notificationService.delete(notificationId);
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
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
