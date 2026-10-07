package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns the first-entry boundary; the counter row lock remains held until the caller commits. */
@Service
@RequiredArgsConstructor
public class NotificationInboxService {
    private final NotificationMapper mapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enter(Notification notification) {
        mapper.ensureInboxCounter(notification.getUserId());
        long head = mapper.lockInboxHead(notification.getUserId());
        Long existing = mapper.selectInboxSequence(notification.getId(), notification.getUserId());
        if (existing != null) {
            notification.setInboxSeq(existing);
            return;
        }
        long next = Math.addExact(head, 1L);
        if (mapper.assignInboxSequence(notification.getId(), notification.getUserId(), next) != 1
                || mapper.advanceInboxHead(notification.getUserId(), head, next) != 1) {
            throw new IllegalStateException("Notification inbox entry changed concurrently");
        }
        notification.setInboxSeq(next);
    }
}
