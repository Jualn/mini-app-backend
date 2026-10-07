package cn.jualn.miniapp.infrastructure.async.job;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobExecutionContextTest {
    @Test
    void renewAndLockedOwnershipUseTheSameAttemptToken() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        when(mapper.renewLease(7L, "attempt-2", 120)).thenReturn(1);
        when(mapper.lockOwnedAttempt(7L, "attempt-2")).thenReturn(1);
        JobExecutionContext context = new JobExecutionContext(7L, "attempt-2", mapper, Duration.ofSeconds(90));

        context.prepareBatch();
        context.requireOwnership();

        verify(mapper).renewLease(7L, "attempt-2", 120);
        verify(mapper).lockOwnedAttempt(7L, "attempt-2");
    }

    @Test
    void lostAttemptStopsBeforeAnotherBatch() {
        AsyncJobMapper mapper = mock(AsyncJobMapper.class);
        JobExecutionContext context = new JobExecutionContext(7L, "old-attempt", mapper, Duration.ofSeconds(90));

        assertThrows(JobOwnershipLostException.class, context::prepareBatch);
    }
}
