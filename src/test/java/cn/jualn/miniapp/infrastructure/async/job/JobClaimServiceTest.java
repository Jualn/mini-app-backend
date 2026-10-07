package cn.jualn.miniapp.infrastructure.async.job;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobClaimServiceTest {
    @Test
    void claimIncrementsAttemptAndReturnsOnlyCasWinner() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        AsyncJob first = job(1L, 0, 4, null);
        AsyncJob lost = job(2L, 0, 4, null);
        when(mapper.selectDueForUpdate(any(), eq(10))).thenReturn(List.of(first, lost));
        when(mapper.claim(eq(1L), eq("worker"), any())).thenReturn(1);
        when(mapper.claim(eq(2L), eq("worker"), any())).thenReturn(0);

        List<AsyncJob> claimed = new JobClaimService(mapper).claimDue("worker", 10);

        assertEquals(List.of(first), claimed);
        assertEquals(1, first.getAttempt());
        assertEquals("RUNNING", first.getStatus());
    }

    @Test
    void expiredLeaseConsumesAttemptAndBecomesDeadAtBudget() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        AsyncJob expired = job(3L, 4, 4, "old-owner");
        when(mapper.selectExpiredForUpdate(any(), eq(20))).thenReturn(List.of(expired));
        when(mapper.recoverExpired(eq(3L), eq("old-owner"), any(), eq(true), any())).thenReturn(1);

        int recovered = new JobClaimService(mapper).recoverExpired(20, new RetrySchedule());

        assertEquals(1, recovered);
        verify(mapper).recoverExpired(eq(3L), eq("old-owner"), any(), eq(true), any());
    }

    private AsyncJob job(long id, int attempt, int maxAttempts, String owner) {
        AsyncJob job = new AsyncJob();
        job.setId(id);
        job.setAttempt(attempt);
        job.setMaxAttempts(maxAttempts);
        job.setLeaseOwner(owner);
        job.setNextRunAt(LocalDateTime.now());
        return job;
    }
}
