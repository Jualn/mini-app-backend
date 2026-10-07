package cn.jualn.miniapp.infrastructure.async.job;

public interface JobHandler<T> {
    String jobType();

    int schemaVersion();

    Class<T> payloadType();

    void handle(T payload);

    default void handle(T payload, JobExecutionContext context) {
        handle(payload);
    }
}
