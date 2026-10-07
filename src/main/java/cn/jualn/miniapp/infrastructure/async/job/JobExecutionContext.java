package cn.jualn.miniapp.infrastructure.async.job;

import java.time.Duration;
import java.time.LocalDateTime;

/** Attempt-scoped ownership token exposed only to handlers that perform long or remote work. */
public final class JobExecutionContext {
    private static final int LEASE_SECONDS = 120;
    private final long jobId;
    private final String ownershipToken;
    private final AsyncJobMapper mapper;
    private final LocalDateTime budgetDeadline;

    JobExecutionContext(long jobId, String ownershipToken, AsyncJobMapper mapper, Duration executionBudget) {
        this.jobId = jobId;
        this.ownershipToken = ownershipToken;
        this.mapper = mapper;
        this.budgetDeadline = LocalDateTime.now().plus(executionBudget);
    }

    /** Renew before each bounded batch. Budget exhaustion yields to durable retry instead of looping forever. */
    public void prepareBatch() {
        if (!LocalDateTime.now().isBefore(budgetDeadline)) {
            throw new JobYieldException("job execution budget exhausted");
        }
        if (mapper.renewLease(jobId, ownershipToken, LEASE_SECONDS) != 1) {
            throw new JobOwnershipLostException("job ownership lost before batch");
        }
    }

    /** Must be called inside the same local transaction as the protected business write. */
    public void requireOwnership() {
        if (mapper.lockOwnedAttempt(jobId, ownershipToken) != 1) {
            throw new JobOwnershipLostException("job ownership lost before business write");
        }
    }

    public long jobId() {
        return jobId;
    }

    public String ownershipToken() {
        return ownershipToken;
    }
}
