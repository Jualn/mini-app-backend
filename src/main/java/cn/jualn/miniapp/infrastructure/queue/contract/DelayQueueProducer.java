package cn.jualn.miniapp.infrastructure.queue.contract;

import java.time.LocalDateTime;

/**
 * 延迟队列生产者接口。
 *
 * <p>与 {@link QueueProducer} 的区别：需要指定触发时间，
 * 底层用 Redis ZSET 存储，score = 触发时间戳（秒）。</p>
 */
public interface DelayQueueProducer {
    /**
     * 发送延迟消息。
     *
     * @param payload   消息载荷
     * @param sendAt    期望触发时间
     * @param memberKey ZSET 成员唯一标识（如 "nq:{notifyQueueId}"），用于去重和取消
     */
    <T extends MessagePayload> void send(T payload, LocalDateTime sendAt, String memberKey);

    /**
     * 取消延迟消息（从 ZSET 移除）。
     *
     * @param memberKey 入队时使用的成员标识
     */
    void cancel(String memberKey);
}
