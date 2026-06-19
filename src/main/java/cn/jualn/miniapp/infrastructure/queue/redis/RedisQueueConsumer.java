package cn.jualn.miniapp.infrastructure.queue.redis;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.dispatch.MessageDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RedisQueueConsumer implements SmartLifecycle {
    // 实现 SmartLifecycle 接口可以让 Spring 显式地在销毁 Bean 之前调用你的 stop() 方法，
    // 并等待它执行完成。ContextClosedEvent 的触发时间与Bean销毁不同步，可能导致连接池销毁后，consumeLoop还在执行
    // 由于 running 标志位的更改可能稍慢于连接工厂的销毁，就会导致IllegalStateException

    private static final long REDIS_ERROR_LOG_INTERVAL_MS = 30_000L;
    private static final long REDIS_RETRY_INITIAL_BACKOFF_MS = 1_000L;
    private static final long REDIS_RETRY_MAX_BACKOFF_MS = 30_000L;

    private final Executor handlerExecutor;
    private final MessageDispatcher messageDispatcher;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private volatile boolean running = false;
    private boolean redisUnavailable = false;
    private long nextRedisErrorLogAt = 0L;
    private int suppressedRedisErrorCount = 0;
    private long retryBackoffMs = REDIS_RETRY_INITIAL_BACKOFF_MS;
    private static final int MAX_RETRY = 3;

    public RedisQueueConsumer(@Qualifier("consumerExecutor") Executor handlerExecutor, MessageDispatcher messageDispatcher, RedisService redisService, ObjectMapper objectMapper) {
        this.handlerExecutor = handlerExecutor;
        this.messageDispatcher = messageDispatcher;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        this.running = true;
        Thread thread = new Thread(this::consumeLoop, "queue-redis-consumer");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        this.running = false; // 停止循环
        // 如果 consumeLoop 可能阻塞在 blpop/rightPop，建议在此处打断线程
        // thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    // 确保在 Redis bean 销毁之前执行
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    /**
     * 通过 Redis 获取队列消息
     * 反序列化基础消息，获取 topic
     * 提交线程池，绑定了 MDC 和 context，提交给 consumer
     */
    private void consumeLoop() {
        while (running) {
            try {
                // 设置阻塞轮询时间，执行 redis 指令后，等待的时间，有数据直接响应，没数据就等待
                // 等待就是阻塞，所以在设置最大响应时间必须大于这个阻塞时间，不然会报错
                Object rawMsg = redisService.dequeue(RedisKeyConstant.QUEUE_MAIN, 3, TimeUnit.SECONDS);
                String msg = rawMsg == null ? null : String.valueOf(rawMsg);

                markRedisRecoveredIfNeeded();

                // 当没有数据时
                if (msg == null) {
                    continue;// 继续阻塞等待
                }

                // 只反序列化基础部分，不反序列化泛型，用于获取 topic
//                QueueMessage<?> message = objectMapper.readValue(msg, QueueMessage.class);
                JsonNode jsonNode = objectMapper.readTree(msg);

                try {
                    handlerExecutor.execute(() -> consumeMessage(msg, jsonNode));
                } catch (RejectedExecutionException e) {
                    retryOrDeadLetter(msg, jsonNode, e);
                }

            } catch (RedisConnectionFailureException e) {
                handleRedisConnectionFailure(e);
            } catch (Exception e) {
                log.error("[RedisQueue][异常] consumer loop failed", e);
            }
        }
    }

    private void consumeMessage(String msg, JsonNode jsonNode) {
        String topic = jsonNode.path("topic").asText("");
        String traceId = jsonNode.path("traceId").asText("");

        try {
            MDC.put("traceId", traceId);
            UserContext.setUserId(jsonNode.path("userId").asLong());

            messageDispatcher.dispatch(topic, msg);

        } catch (Exception e) {
            retryOrDeadLetter(msg, jsonNode, e);
        } finally {
            MDC.clear();
            UserContext.clear();
        }
    }

    private void retryOrDeadLetter(String msg, JsonNode jsonNode, Exception e) {
        String topic = jsonNode.path("topic").asText("");
        String traceId = jsonNode.path("traceId").asText("");
        int retryCount = jsonNode.path("retryCount").asInt(0);

        if (isNonRetryable(e) || retryCount >= MAX_RETRY) {
            redisService.enqueue(RedisKeyConstant.QUEUE_DEAD, msg);

            log.error("[RedisConsumer][死信] topic={}, traceId={}, retryCount={}",
                    topic, traceId, retryCount, e);
            return;
        }

        int nextRetryCount = retryCount + 1;
        String retryJson = increaseRetryCount(msg, nextRetryCount);

        long delaySeconds = retryDelaySeconds(nextRetryCount);
        String memberKey = "retry:" + topic + ":" + traceId + ":" + UUID.randomUUID();
        double score = Instant.now().plusSeconds(delaySeconds).getEpochSecond();

        redisService.set(
                RedisKeyConstant.delayMsgBody(memberKey),
                retryJson,
                Duration.ofDays(3)
        );
        redisService.zAdd(RedisKeyConstant.NOTIFY_DELAY_ZSET, memberKey, score);

        log.warn("[RedisConsumer][重试] topic={}, traceId={}, retryCount={}->{}, delay={}s",
                topic, traceId, retryCount, nextRetryCount, delaySeconds, e);
    }

    private String increaseRetryCount(String msg, int retryCount) {
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(msg);
            node.put("retryCount", retryCount);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("更新 retryCount 失败", e);
        }
    }

    private long retryDelaySeconds(int retryCount) {
        return switch (retryCount) {
            case 1 -> 10L;
            case 2 -> 60L;
            default -> 300L;
        };
    }

    private boolean isNonRetryable(Exception e) {
        String message = e.getMessage();

        if (e instanceof IllegalArgumentException) {
            return true;
        }

        if (message != null && message.contains("Unknown topic")) {
            return true;
        }

        if (message != null && message.contains("反序列化")) {
            return true;
        }

        return false;
    }

    private void handleRedisConnectionFailure(RedisConnectionFailureException e) {
        long now = System.currentTimeMillis();

        if (!redisUnavailable) {
            redisUnavailable = true;
            suppressedRedisErrorCount = 0;
            nextRedisErrorLogAt = now + REDIS_ERROR_LOG_INTERVAL_MS;
            log.warn("[RedisQueue][警告] redis unavailable, consumer will retry with backoff={}ms", retryBackoffMs, e);
        } else if (now >= nextRedisErrorLogAt) {
            log.warn("[RedisQueue][警告] redis still unavailable, suppressedErrorCount={}, nextBackoff={}ms",
                    suppressedRedisErrorCount, retryBackoffMs);
            suppressedRedisErrorCount = 0;
            nextRedisErrorLogAt = now + REDIS_ERROR_LOG_INTERVAL_MS;
        } else {
            suppressedRedisErrorCount++;
        }

        sleepWithBackoff();
    }

    private void markRedisRecoveredIfNeeded() {
        if (!redisUnavailable) {
            return;
        }

        log.info("[RedisQueue] redis connection recovered, suppressedErrorCount={}", suppressedRedisErrorCount);
        redisUnavailable = false;
        suppressedRedisErrorCount = 0;
        nextRedisErrorLogAt = 0L;
        retryBackoffMs = REDIS_RETRY_INITIAL_BACKOFF_MS;
    }

    private void sleepWithBackoff() {
        long jitterMs = ThreadLocalRandom.current().nextLong(200L, 801L);
        long sleepMs = Math.min(retryBackoffMs + jitterMs, REDIS_RETRY_MAX_BACKOFF_MS);

        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            running = false;
            return;
        }

        retryBackoffMs = Math.min(retryBackoffMs * 2, REDIS_RETRY_MAX_BACKOFF_MS);
    }
}
