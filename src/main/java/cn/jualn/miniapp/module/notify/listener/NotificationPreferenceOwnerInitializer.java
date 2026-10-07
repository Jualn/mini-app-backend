package cn.jualn.miniapp.module.notify.listener;

import cn.jualn.miniapp.module.notify.service.NotificationPreferenceBridgeService;
import cn.jualn.miniapp.module.user.event.UserCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationPreferenceOwnerInitializer {
    private final NotificationPreferenceBridgeService preferenceBridgeService;

    @EventListener
    public void initialize(UserCreatedEvent event) {
        preferenceBridgeService.initializeCanonicalOwner(event.userId());
    }
}
