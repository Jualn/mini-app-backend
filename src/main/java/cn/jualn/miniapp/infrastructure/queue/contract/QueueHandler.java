package cn.jualn.miniapp.infrastructure.queue.contract;

public interface QueueHandler<T extends MessagePayload> {

    void handle(QueueMessage<T> message);
}
