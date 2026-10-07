package cn.jualn.miniapp.infrastructure.async.message;

public interface EventHandler<T> {
    String topic();
    int schemaVersion();
    Class<T> payloadType();
    void handle(T payload, MessageEnvelope envelope);
}
