package cn.jualn.miniapp.infrastructure.queue.redis;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.annotation.QueueTopic;
import cn.jualn.miniapp.infrastructure.queue.contract.MessagePayload;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueMessage;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisQueueProducer implements QueueProducer {
    private final ObjectMapper objectMapper;
    private final RedisService redisService;

    @Override
    public <T extends MessagePayload> void send(T payload) {
        Class<?> clazz = payload.getClass();
        String topic  = resolveTopic(clazz);

        String traceId = MDC.get("traceId");
        QueueMessage<T> msg = QueueMessage.<T>builder()
                .topic(topic)
                .traceId(traceId)
                .userId(UserContext.getUserId())
                .retryCount(0)
                .payload(payload)
                .build();

        String json;
        try {
            json = objectMapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new SystemException("序列化队列消息失败", e);
        }

        doSend(json);
    }

    private void doSend(String json) {
        // 使用一个队列，根据每个value中的topic分配handler
        // 多消费者 queue: + topic 太吃性能
        String key = RedisKeyConstant.QUEUE_MAIN;
        try {
            log.debug("[Producer] 入队 key={}, value={}", key, json);
            Long size = redisService.enqueue(key, json);
            if (size == null) {
                throw new SystemException("RedisQueue 入队失败");
            }
        } catch (Exception e) {
            if (e instanceof SystemException systemException) {
                throw systemException;
            }
            throw new SystemException("RedisQueue 入队失败", e);
        }
    }

    // ===== topic 生成 =====
    private String resolveTopic(Class<?> clazz) {
        QueueTopic annotation = clazz.getAnnotation(QueueTopic.class);
        if (annotation != null) {
            return annotation.value();
        }
        String name = clazz.getSimpleName().replace("Payload", "");
        return name.replaceAll("([a-z])([A-Z])", "$1.$2").toLowerCase();
    }
}
