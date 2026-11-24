package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.mapper.NotificationLifecycleMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.service.impl.NotificationCreationService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Reconstructible bounded maintenance. No cleanup Job or remote work inside transactions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationLifecycleService {
    private static final int BATCH = 100;
    private final NotificationLifecycleMapper lifecycle;
    private final NotificationMapper notifications;
    private final TransactionTemplate transactions;
    private final MeterRegistry meters;
    private final java.util.concurrent.atomic.AtomicLong backlog = new java.util.concurrent.atomic.AtomicLong(-1);
    private final java.util.concurrent.atomic.AtomicLong unclassified = new java.util.concurrent.atomic.AtomicLong(-1);

    @jakarta.annotation.PostConstruct
    void registerMetrics() {
        meters.gauge("jualn.notification.cleanup.backlog", backlog);
        meters.gauge("jualn.notification.cleanup.unclassified", unclassified);
    }


    @Scheduled(fixedDelayString = "${notification.cleanup.interval:60000}")
    public void cleanup() {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        try {
            for (int batch = 0; batch < 10 && System.nanoTime() < deadline; batch++) {
                if (cleanupBatch(NotificationObservation.now()) == 0) break;
            }
            backlog.set(lifecycle.backlog(NotificationObservation.now()));
            unclassified.set(lifecycle.unclassifiedBacklog(NotificationObservation.now()));
        } catch (RuntimeException failure) {
            meters.counter("jualn.notification.cleanup", "result", "failure").increment();
            log.error("result=failure errorCategory=internal notificationCleanup exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    public int cleanupBatch(LocalDateTime now) {
        int processed = 0;
        for (long id : lifecycle.expiredPending(now, BATCH)) {
            int expired = transactions.execute(tx -> lifecycle.expireDelivery(id, now));
            processed += expired;
            meters.counter("jualn.notification.cleanup", "result", "expired_delivery").increment(expired);
        }
        for (var candidate : lifecycle.candidates(now, BATCH)) {
            Integer removed = transactions.execute(tx -> {
                if (!preserveSource(candidate)) return 0;
                notifications.ensureInboxCounter(candidate.getUserId());
                notifications.lockInboxHead(candidate.getUserId());
                var current = lifecycle.lock(candidate.getId());
                if (current == null) return 0;
                int count = lifecycle.dispose(current.getId(), now);
                if (count > 0) {
                    lifecycle.markDisposed(current.getId(), now);
                    notifications.advanceReadVersion(current.getUserId());
                }
                return count;
            });
            processed += removed;
            meters.counter("jualn.notification.cleanup", "result", "disposed").increment(removed);
        }
        for (long id : lifecycle.staleProcessing(now, BATCH)) {
            processed += transactions.execute(tx -> lifecycle.unknownStaleProcessing(id));
        }
        for (long id : lifecycle.expiredEvidence(now, BATCH)) {
            int redacted = transactions.execute(tx -> {
                var candidate = notifications.selectById(id);
                if (candidate == null) return 0;
                if (!preserveSource(candidate)) return 0;
                lifecycle.lock(id);
                int count = lifecycle.redact(id, now);
                if (count > 0) lifecycle.redactTemplates(id);
                return count;
            });
            processed += redacted;
            meters.counter("jualn.notification.cleanup", "result", "redacted").increment(redacted);
        }
        meters.counter("jualn.notification.cleanup", "result", "processed").increment(processed);
        return processed;
    }

    private boolean preserveSource(cn.jualn.miniapp.module.notify.entity.Notification candidate) {
        if (candidate.getSourceKey() == null || candidate.getSourceKey().isBlank()) return false;
        String source = NotificationCreationService.digest(candidate.getSourceKey());
        notifications.claimSource(source, NotificationCreationService.identity(candidate), null, candidate.getCreatedAt());
        var receipt = notifications.lockSource(source);
        if (receipt == null) return false;
        if (Long.valueOf(0).equals(receipt.getNotificationId())) {
            if (!NotificationCreationService.identity(candidate).equals(receipt.getIdentityDigest())) return false;
            notifications.finishSource(source, candidate.getId(), candidate.getCreatedAt());
            receipt = notifications.lockSource(source);
        }
        return receipt != null && java.util.Objects.equals(receipt.getNotificationId(), candidate.getId())
                && java.util.Objects.equals(receipt.getOriginalCreatedAt(), candidate.getCreatedAt())
                && receipt.getDisposedAt() == null;
    }
}
