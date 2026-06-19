package cn.jualn.miniapp.infrastructure.queue.contract;

public interface QueueProducer {

    <T extends MessagePayload> void send(T payload);
}
