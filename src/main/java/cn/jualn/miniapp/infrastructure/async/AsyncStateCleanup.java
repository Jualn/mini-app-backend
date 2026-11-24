package cn.jualn.miniapp.infrastructure.async;

import cn.jualn.miniapp.infrastructure.async.job.AsyncJobMapper;
import cn.jualn.miniapp.infrastructure.async.outbox.OutboxMapper;
import cn.jualn.miniapp.infrastructure.async.message.EventConsumptionMapper;
import cn.jualn.miniapp.infrastructure.async.message.DeadMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class AsyncStateCleanup {
    private static final int BATCH = 200;
    private final AsyncJobMapper jobs;
    private final OutboxMapper outbox;
    private final EventConsumptionMapper eventConsumptions;
    private final DeadMessageMapper deadMessages;

    @Scheduled(cron = "${async-processing.cleanup.cron:0 25 4 * * *}", zone = "Asia/Shanghai")
    @Transactional
    public void cleanupOneBatch() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        outbox.deletePublishedBefore(now.minusDays(14), BATCH);
        outbox.deleteDeadBefore(now.minusDays(90), BATCH);
        jobs.deleteCompletedBefore("SUCCEEDED", now.minusDays(30), BATCH);
        jobs.deleteCompletedBefore("CANCELLED", now.minusDays(30), BATCH);
        jobs.deleteCompletedBefore("DEAD", now.minusDays(90), BATCH);
        eventConsumptions.deleteBefore(now.minusDays(21), BATCH);
        deadMessages.deleteBefore(now.minusDays(90), BATCH);
    }
}
