package cn.jualn.miniapp.module.notify.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A single observation boundary for all statements in one notification transaction. */
public final class NotificationObservation {
    private NotificationObservation() { }
    public static LocalDateTime now() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                if (synchronization instanceof Boundary boundary) return boundary.time;
            }
            var boundary = new Boundary(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
            TransactionSynchronizationManager.registerSynchronization(boundary);
            return boundary.time;
        }
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }
    private static final class Boundary implements TransactionSynchronization {
        private final LocalDateTime time;
        private Boundary(LocalDateTime time) { this.time = time; }
    }
}
