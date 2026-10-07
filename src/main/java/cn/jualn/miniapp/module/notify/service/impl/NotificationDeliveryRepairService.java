package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.module.notify.mapper.NotificationDeliveryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded convergence for delivery Jobs whose final attempt crashed or exhausted. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDeliveryRepairService {
    private static final int REPAIR_BATCH = 50;
    private final NotificationDeliveryMapper deliveryMapper;

    @Scheduled(fixedDelayString = "${notification.delivery.repair-interval:60000}")
    @Transactional
    public void repairDeadJobResults() {
        int repaired = deliveryMapper.repairDeadJobResults(REPAIR_BATCH);
        if (repaired > 0) {
            log.warn("result=repaired notificationDeliveryDeadJobResults={}", repaired);
        }
    }
}
