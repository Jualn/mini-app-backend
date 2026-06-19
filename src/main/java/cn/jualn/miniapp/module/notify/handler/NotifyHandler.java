package cn.jualn.miniapp.module.notify.handler;

import cn.jualn.miniapp.infrastructure.queue.contract.QueueHandler;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueMessage;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通知消费 Handler（按 NOTIFICATION_DESIGN.md 设计）。
 * <p>
 * 职责（简化为仅调用 service）：
 * - 从队列消费 NotifyPayload
 * - 委托 service 处理所有业务逻辑
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyHandler implements QueueHandler<NotifyPayload> {

    private final NotifyService notifyService;

    @Override
    public void handle(QueueMessage<NotifyPayload> message) {
        NotifyPayload payload = message.getPayload();
        // Handler 只负责调用 service，所有业务逻辑由 service 处理
        notifyService.processNotificationPayload(payload);
    }
}
