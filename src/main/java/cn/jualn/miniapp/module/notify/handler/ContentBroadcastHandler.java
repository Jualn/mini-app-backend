package cn.jualn.miniapp.module.notify.handler;

import cn.jualn.miniapp.infrastructure.queue.contract.QueueHandler;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueMessage;
import cn.jualn.miniapp.module.notify.payload.BroadcastPlanPayload;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class ContentBroadcastHandler implements QueueHandler<BroadcastPlanPayload> {

    private final NotifyService notifyService;

    @Override
    public void handle(QueueMessage<BroadcastPlanPayload> message) {
        // Handler 只负责调用 service，所有业务逻辑由 service 处理
        Long planId = message.getPayload().getPlanId();
        notifyService.broadcastPlanFanOut(planId);
    }
}
