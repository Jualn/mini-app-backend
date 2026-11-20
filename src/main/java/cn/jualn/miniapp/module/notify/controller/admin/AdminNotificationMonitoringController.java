package cn.jualn.miniapp.module.notify.controller.admin;
import cn.jualn.miniapp.module.notify.service.NotificationMonitoringService;
import cn.jualn.miniapp.module.notify.converter.NotificationCatalogConverter;
import cn.jualn.miniapp.module.notify.vo.NotificationCatalogVO;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import lombok.RequiredArgsConstructor;
import java.time.OffsetDateTime;
@RestController @RequiredArgsConstructor @RequestMapping("/v1/admin")
public class AdminNotificationMonitoringController {
    private final NotificationMonitoringService service;
    private final NotificationCatalogConverter converter;
    @GetMapping("/notification-channel-status")
    public ResponseEntity<NotificationCatalogVO.Status> status(){return noStore(converter.status(service.status(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong())));}
    @GetMapping("/notification-delivery-summary")
    public ResponseEntity<NotificationCatalogVO.Summary> summary(@RequestParam OffsetDateTime from,@RequestParam OffsetDateTime until,
            @RequestParam(required=false) NotificationChannel channel,@RequestParam(required=false) NotificationCategory category,
            @RequestParam java.util.Map<String,String> parameters){
        cn.jualn.miniapp.module.notify.controller.CanonicalNotificationController.validateParameters(parameters,java.util.Set.of("from","until","channel","category"));
        return noStore(converter.summary(service.summary(cn.jualn.miniapp.common.security.AdminStpUtil.STP_LOGIC.getLoginIdAsLong(),from,until,channel,category)));
    }
    private <T>ResponseEntity<T> noStore(T body){return ResponseEntity.ok().header("Cache-Control","no-store").body(body);}
}
