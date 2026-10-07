package cn.jualn.miniapp.infrastructure.async.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JobHandlerRegistryTest {
    record Payload(String value) {}

    @Test
    void dispatchesByExplicitTypeAndVersion() throws Exception {
        AtomicReference<String> handled = new AtomicReference<>();
        JobHandler<Payload> handler = handler(handled);
        JobHandlerRegistry registry = new JobHandlerRegistry(new ObjectMapper(), List.of(handler));
        AsyncJob job = new AsyncJob();
        job.setJobType("sample.run");
        job.setSchemaVersion(1);
        job.setPayload("{\"value\":\"ok\"}");

        registry.dispatch(job);

        assertEquals("ok", handled.get());
    }

    @Test
    void rejectsUnknownVersionAsPermanentRegistryFailure() {
        JobHandlerRegistry registry = new JobHandlerRegistry(new ObjectMapper(), List.of(handler(new AtomicReference<>())));
        AsyncJob job = new AsyncJob();
        job.setJobType("sample.run");
        job.setSchemaVersion(2);
        job.setPayload("{}");

        assertThrows(JobHandlerRegistry.UnsupportedJobException.class, () -> registry.dispatch(job));
        assertEquals("unsupported", registry.metricType("attacker-controlled-" + System.nanoTime(), 99));
    }

    private JobHandler<Payload> handler(AtomicReference<String> handled) {
        return new JobHandler<>() {
            @Override public String jobType() { return "sample.run"; }
            @Override public int schemaVersion() { return 1; }
            @Override public Class<Payload> payloadType() { return Payload.class; }
            @Override public void handle(Payload payload) { handled.set(payload.value()); }
        };
    }
}
