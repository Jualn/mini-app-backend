package cn.jualn.miniapp.infrastructure.queue.redis;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.dispatch.MessageDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis ZSET 的延迟队列消费者。
 *
 * <p>与 {@link RedisQueueConsumer}（BLPOP 被动监听）的区别：</p>
 * <ul>
 *   <li>主动轮询，每 {@value POLL_INTERVAL_SECONDS} 秒检查一次到期任务</li>
 *   <li>score = 触发时间戳，取 score ≤ 当前时间的任务</li>
 *   <li>消费成功后 ZREM + 删消息体 key；失败保留，下轮自动重试</li>
 * </ul>
 *
 * <p>两个消费者共用同一个 {@code consumerExecutor} 线程池处理任务，
 * 但监听线程各自独立，互不阻塞。</p>
 */
@Slf4j
@Component
public class RedisDelayQueueConsumer implements SmartLifecycle {
    private static final int POLL_INTERVAL_SECONDS = 30;
    private static final int POLL_BATCH_SIZE = 50;
    private static final int MAX_RETRY = 3;

    private final Executor handlerExecutor;
    private final MessageDispatcher messageDispatcher;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    private volatile boolean running = false;

    public RedisDelayQueueConsumer(
            @Qualifier("consumerExecutor") Executor handlerExecutor,
            MessageDispatcher messageDispatcher,
            RedisService redisService,
            ObjectMapper objectMapper) {
        this.handlerExecutor = handlerExecutor;
        this.messageDispatcher = messageDispatcher;
        this.redisService = redisService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        this.running = true;
        Thread thread = new Thread(this::pollLoop, "queue-delay-consumer");
        thread.setDaemon(true);
        thread.start();
        log.info("[DelayQueue] 延迟队列监听线程启动，轮询间隔={}s", POLL_INTERVAL_SECONDS);
    }

    @Override
    public void stop() {
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return this.running;
    }

    // 与 RedisQueueConsumer 保持相同 Phase，Spring 关闭时同步停止
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void pollLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                poll();
            } catch (Exception e) {
                log.error("[DelayQueue] 轮询异常，等待下一轮", e);
            }

            try {
                TimeUnit.SECONDS.sleep(POLL_INTERVAL_SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("[DelayQueue] 监听线程收到中断，退出");
                break;
            }
        }
    }

    private void poll() {
        double now = Instant.now().getEpochSecond();

        // 取所有 score ≤ 当前时间的成员，最多 POLL_BATCH_SIZE 条
        Set<ZSetOperations.TypedTuple<Object>> dues =
                redisService.zRangeByScore(
                        RedisKeyConstant.NOTIFY_DELAY_ZSET, 0, now, 0, POLL_BATCH_SIZE);

        if (dues.isEmpty()) {
            log.debug("[DelayQueue] 本轮无到期任务");
            return;
        }

        log.debug("[DelayQueue] 本轮取到 {} 条到期任务", dues.size());

        for (ZSetOperations.TypedTuple<Object> tuple : dues) {
            String memberKey = String.valueOf(tuple.getValue());

            try {
                handlerExecutor.execute(() -> consume(memberKey));
            } catch (RejectedExecutionException e) {
                log.warn("[DelayQueue] 线程池拒绝执行，等待下一轮重试，memberKey={}", memberKey, e);
            }
        }
    }

    /**
     * 消费单条延迟任务。
     *
     * <p>从配套 key 取完整 JSON → 解析 topic → 交给 MessageDispatcher。
     * 成功后 ZREM + 删 msgBody；失败保留，下轮自动重试。</p>
     */
    private void consume(String memberKey) {
        String msgBodyKey = RedisKeyConstant.delayMsgBody(memberKey);

        /*
         * 先抢占，避免多实例重复消费。
         *
         * 注意：
         * 如果你的 redisService.zRemove 当前是 void，
         * 建议新增一个 zRemoveCount(...) 方法返回删除数量。
         */
        long removed = redisService.zRemove(RedisKeyConstant.NOTIFY_DELAY_ZSET, memberKey);
        if (removed == 0) {
            return;
        }

        String json = redisService.getString(msgBodyKey);

        if (json == null) {
            log.warn("[DelayQueue] 消息体不存在，跳过，memberKey={}", memberKey);
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(json);
            String topic = node.path("topic").asText("");
            String traceId = node.path("traceId").asText("");

            MDC.put("traceId", traceId);
            UserContext.setUserId(node.path("userId").asLong());

            messageDispatcher.dispatch(topic, json);

            redisService.delete(msgBodyKey);

            log.debug("[DelayQueue] 消费成功，memberKey={}, topic={}", memberKey, topic);

        } catch (Exception e) {
            retryOrDeadLetter(json, memberKey, e);
        } finally {
            MDC.clear();
            UserContext.clear();
        }
    }

    private void retryOrDeadLetter(String json, String memberKey, Exception e) {
        try {
            JsonNode node = objectMapper.readTree(json);

            String topic = node.path("topic").asText("");
            String traceId = node.path("traceId").asText("");
            int retryCount = node.path("retryCount").asInt(0);

            if (isNonRetryable(e) || retryCount >= MAX_RETRY) {
                redisService.enqueue(RedisKeyConstant.QUEUE_DEAD, json);
                redisService.delete(RedisKeyConstant.delayMsgBody(memberKey));

                log.error("[DelayQueue][死信] memberKey={}, topic={}, traceId={}, retryCount={}",
                        memberKey, topic, traceId, retryCount, e);
                return;
            }

            int nextRetryCount = retryCount + 1;
            String retryJson = increaseRetryCount(json, nextRetryCount);

            long delaySeconds = retryDelaySeconds(nextRetryCount);
            double nextScore = Instant.now().plusSeconds(delaySeconds).getEpochSecond();

            redisService.set(
                    RedisKeyConstant.delayMsgBody(memberKey),
                    retryJson,
                    Duration.ofDays(3)
            );
            redisService.zAdd(RedisKeyConstant.NOTIFY_DELAY_ZSET, memberKey, nextScore);

            log.warn("[DelayQueue][重试] memberKey={}, topic={}, traceId={}, retryCount={}->{}, delay={}s",
                    memberKey, topic, traceId, retryCount, nextRetryCount, delaySeconds, e);

        } catch (Exception ex) {
            redisService.enqueue(RedisKeyConstant.QUEUE_DEAD, json);
            redisService.delete(RedisKeyConstant.delayMsgBody(memberKey));

            log.error("[DelayQueue][死信] 重试处理本身失败，memberKey={}", memberKey, ex);
        }
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

        if (e instanceof IllegalArgumentException || e instanceof BusinessException) {
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
}
