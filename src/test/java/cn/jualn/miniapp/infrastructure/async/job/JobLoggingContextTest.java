package cn.jualn.miniapp.infrastructure.async.job;

import cn.jualn.miniapp.common.observability.ObservabilityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobLoggingContextTest {
    @Test
    void jobAttemptsKeepJobAndOperationIdentityButUseFreshTracesAndRestoreScope() throws Exception {
        var claims = mock(JobClaimService.class);
        var mapper = mock(AsyncJobMapper.class);
        var handlers = mock(JobHandlerRegistry.class);
        AsyncJob job = new AsyncJob();
        job.setId(42L);
        job.setJobType("fixture");
        job.setSchemaVersion(1);
        job.setAttempt(1);
        job.setMaxAttempts(3);
        job.setOperationId("logical-operation");
        job.setLeaseOwner("fixture-owner");
        when(claims.claimDue(anyString(), eq(2))).thenReturn(List.of(job));
        when(handlers.metricType("fixture", 1)).thenReturn("fixture");
        when(mapper.markSucceeded(eq(42L), eq("fixture-owner"), any())).thenReturn(1);
        List<Map<String, String>> attempts = new ArrayList<>();
        doAnswer(invocation -> {
            attempts.add(ObservabilityContext.capture());
            assertNull(MDC.get("private-field"));
            return null;
        }).when(handlers).dispatch(eq(job), any(JobExecutionContext.class));
        // Execute synchronously through the same decorator, exercising the CallerRuns restoration path.
        var worker = new JobWorker(claims, mapper, handlers, new JobFailureClassifier(), new RetrySchedule(),
                command -> new cn.jualn.miniapp.common.async.ContextCopyDecorator().decorate(command).run(),
                new SimpleMeterRegistry());
        MDC.put("traceId", "caller-trace");
        MDC.put("private-field", "synthetic-secret");
        try {
            worker.poll();
            job.setAttempt(2);
            worker.poll();
            assertEquals(2, attempts.size());
            assertNotEquals(attempts.get(0).get("traceId"), attempts.get(1).get("traceId"));
            for (int i = 0; i < 2; i++) {
                assertEquals("42", attempts.get(i).get("jobId"));
                assertEquals("logical-operation", attempts.get(i).get("operationId"));
                assertEquals(Integer.toString(i + 1), attempts.get(i).get("attempt"));
            }
            assertEquals("caller-trace", MDC.get("traceId"));
            assertNull(MDC.get("jobId"));
            assertEquals("synthetic-secret", MDC.get("private-field"));
        } finally { MDC.clear(); }
    }
}
