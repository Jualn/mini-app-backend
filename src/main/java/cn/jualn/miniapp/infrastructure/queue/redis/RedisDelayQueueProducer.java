package cn.jualn.miniapp.infrastructure.queue.redis;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.annotation.QueueTopic;
import cn.jualn.miniapp.infrastructure.queue.contract.DelayQueueProducer;
import cn.jualn.miniapp.infrastructure.queue.contract.MessagePayload;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueMessage;
import cn.jualn.miniapp.infrastructure.queue.dispatch.MessageDispatcher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 基于 Redis ZSET 的延迟队列生产者。
 *
 * <p>与 {@link RedisQueueProducer} 的区别：消息存入 ZSET，score = 触发时间戳（秒）。
 * 消息体 JSON 与普通队列格式完全一致，复用同一套 {@link MessageDispatcher}。</p>
 *
 * <p>ZSET member 存储的是 memberKey（如 "nq:123"），value 存储完整 JSON，
 * 实际上 ZSET 只存 memberKey，JSON 由 DB（notify_queue）持有，消费时从 DB 取。
 * 这样 ZSET 成员体积小，且取消时直接 ZREM memberKey 即可。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDelayQueueProducer implements DelayQueueProducer {
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    @Override
    public <T extends MessagePayload> void send(T payload, LocalDateTime sendAt, String memberKey) {
        // score = 触发时间的 Unix 时间戳（秒） sendAt 是北京时间，把它转换成 Unix 时间戳后放入 Redis ZSET
        double score = sendAt.toEpochSecond(ZoneOffset.ofHours(8));

        // 把完整消息序列化后存一份，消费时直接拿，不用再查 DB 拼装
        // （DB notify_queue 是持久化备份，Redis ZSET 是触发器）
        String topic = resolveTopic(payload.getClass());
        QueueMessage<T> msg = QueueMessage.<T>builder()
                .topic(topic)
                .traceId(MDC.get("traceId"))
                .userId(UserContext.getUserId())
                .retryCount(0)
                .payload(payload)
                .build();

        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new SystemException("延迟队列消息序列化失败", e);
        }

        // ZSET：member=memberKey，score=触发时间戳
        // 用 Hash 存 member -> json 的映射，供消费时取完整消息
        boolean added = redisService.zAdd(RedisKeyConstant.NOTIFY_DELAY_ZSET, memberKey, score);

        // 配套存 json，key 用 member 关联
        redisService.set(
                RedisKeyConstant.delayMsgBody(memberKey),
                json,
                calcMsgBodyTtl(sendAt)
        );

        log.debug("[DelayProducer] 入队，memberKey={}, sendAt={}, topic={}", memberKey, sendAt, topic);
        if (!added) {
            log.warn("[DelayProducer] memberKey 已存在（重复入队？），已覆盖 score，memberKey={}", memberKey);
        }
    }

    @Override
    public void cancel(String memberKey) {
        redisService.zRemove(RedisKeyConstant.NOTIFY_DELAY_ZSET, memberKey);
        redisService.delete(RedisKeyConstant.delayMsgBody(memberKey));
        log.debug("[DelayProducer] 取消延迟任务，memberKey={}", memberKey);
    }

    private String resolveTopic(Class<?> clazz) {
        QueueTopic annotation = clazz.getAnnotation(QueueTopic.class);
        if (annotation != null) return annotation.value();
        String name = clazz.getSimpleName().replace("Payload", "");
        return name.replaceAll("([a-z])([A-Z])", "$1.$2").toLowerCase();
    }

    // 消息体 TTL = 从现在到触发时间的延迟 + 7 天（过期后再保留一段时间，防止服务短暂停机、线程池繁忙、Redis 轮询延迟等情况）
    private Duration calcMsgBodyTtl(LocalDateTime sendAt) {
        Duration delay = Duration.between(
                LocalDateTime.now(),
                sendAt
        );

        if (delay.isNegative()) {
            delay = Duration.ZERO;
        }

        // 到期后再保留 7 天，防止服务短暂停机、线程池繁忙、Redis 轮询延迟等情况
        return delay.plusDays(7);
    }
}
