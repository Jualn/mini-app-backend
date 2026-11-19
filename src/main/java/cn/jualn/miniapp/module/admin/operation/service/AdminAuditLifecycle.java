package cn.jualn.miniapp.module.admin.operation.service;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
/** Reconciles only the deadlines captured in immutable audit facts, never present-day grants. */
@Service @RequiredArgsConstructor
public class AdminAuditLifecycle {
    private final AdminAuditJournal journal;
    @Scheduled(fixedDelay=60000)
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void expire() {journal.expireRecordedFacts(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));}
}
