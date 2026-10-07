package cn.jualn.miniapp.infrastructure.async.job;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JobClaimService {
    private static final Duration LEASE = Duration.ofSeconds(120);
    private final AsyncJobMapper mapper;

    @Transactional
    public List<AsyncJob> claimDue(String owner, int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<AsyncJob> selected = mapper.selectDueForUpdate(now, limit);
        List<AsyncJob> claimed = new ArrayList<>(selected.size());
        for (AsyncJob job : selected) {
            if (mapper.claim(job.getId(), owner, now.plus(LEASE)) == 1) {
                job.setStatus("RUNNING");
                job.setAttempt(job.getAttempt() + 1);
                job.setLeaseOwner(owner);
                job.setLeaseUntil(now.plus(LEASE));
                claimed.add(job);
            }
        }
        return claimed;
    }

    @Transactional
    public int recoverExpired(int limit, RetrySchedule retrySchedule) {
        LocalDateTime now = LocalDateTime.now();
        int recovered = 0;
        for (AsyncJob job : mapper.selectExpiredForUpdate(now, limit)) {
            boolean dead = job.getAttempt() >= job.getMaxAttempts();
            LocalDateTime next = dead ? now : now.plus(retrySchedule.delay(job.getAttempt()));
            recovered += mapper.recoverExpired(job.getId(), job.getLeaseOwner(), next, dead, now);
        }
        return recovered;
    }
}
