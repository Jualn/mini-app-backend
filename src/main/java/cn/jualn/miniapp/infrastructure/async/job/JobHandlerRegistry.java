package cn.jualn.miniapp.infrastructure.async.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class JobHandlerRegistry {
    private final ObjectMapper objectMapper;
    private final Map<Key, JobHandler<?>> handlers = new HashMap<>();

    public JobHandlerRegistry(@Qualifier("objectMapper") ObjectMapper objectMapper,
                              List<JobHandler<?>> candidates) {
        this.objectMapper = objectMapper;
        for (JobHandler<?> handler : candidates) {
            JobHandler<?> previous = handlers.put(new Key(handler.jobType(), handler.schemaVersion()), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate async job handler: " + handler.jobType());
            }
        }
    }

    public void dispatch(AsyncJob job, JobExecutionContext context) throws Exception {
        JobHandler<?> handler = handlers.get(new Key(job.getJobType(), job.getSchemaVersion()));
        if (handler == null) {
            throw new UnsupportedJobException(job.getJobType(), job.getSchemaVersion());
        }
        dispatchTyped(handler, job.getPayload(), context);
    }

    /** Test and non-worker compatibility entry; durable workers always supply an ownership context. */
    public void dispatch(AsyncJob job) throws Exception {
        JobHandler<?> handler = handlers.get(new Key(job.getJobType(), job.getSchemaVersion()));
        if (handler == null) throw new UnsupportedJobException(job.getJobType(), job.getSchemaVersion());
        dispatchWithoutContext(handler, job.getPayload());
    }

    public String metricType(String type, int version) {
        return handlers.containsKey(new Key(type, version)) ? type : "unsupported";
    }

    private <T> void dispatchTyped(JobHandler<T> handler, String json, JobExecutionContext context) throws Exception {
        handler.handle(objectMapper.readValue(json, handler.payloadType()), context);
    }

    private <T> void dispatchWithoutContext(JobHandler<T> handler, String json) throws Exception {
        handler.handle(objectMapper.readValue(json, handler.payloadType()));
    }

    private record Key(String type, int version) {
    }

    public static final class UnsupportedJobException extends RuntimeException {
        public UnsupportedJobException(String type, int version) {
            super("Unsupported async job type/version: " + type + "/" + version);
        }
    }
}
