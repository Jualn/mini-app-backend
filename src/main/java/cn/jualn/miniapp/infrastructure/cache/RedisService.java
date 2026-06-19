package cn.jualn.miniapp.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Redis 基础设施封装。
 *
 * <p>业务层统一通过本类访问 Redis，禁止在 Service 中直接注入 RedisTemplate。</p>
 *
 * <h2>读取方法选择原则</h2>
 * <pre>
 *   getString(key)         → 存的是纯字符串时用，如 "1"、"__NULL__"、access_token。
 *                            注意：存的是对象却调 getString，只会得到 null，不会报错。
 *
 *   get(key, Class)        → 存的是结构化对象（VO/DTO）时用，如 PostDetailVO、UserSettingVO。
 *                            底层由 Jackson 反序列化，类型必须与写入时一致。
 *                            返回 null 表示未命中或类型不匹配，业务层统一走 DB 兜底。
 *
 *   getLong(key)           → 存的是计数器（点赞数、浏览量、未读数）时用。
 *                            Redis 的 INCR/DECR 结果在 Java 侧是 Long，用此方法避免强转。
 * </pre>
 *
 * <h2>NULL 占位符约定</h2>
 * <pre>
 *   防缓存穿透：查 DB 为 null 时，写入 NULL_VALUE 占位，TTL 建议 30s。
 *   读取后用 isNullPlaceholder(value) 判断，命中则直接抛 NOT_FOUND，不穿透 DB。
 *   适用场景：postDetail、activityDetail、examDetail、targetExists。
 * </pre>
 *
 * <h2>各数据结构对应业务</h2>
 * <pre>
 *   String  → 缓存（用户信息、内容详情、Token、状态标记、计数器）
 *   ZSet    → 延迟队列（NOTIFY_DELAY_ZSET，score = 推送时间戳）
 *   List    → 普通任务队列（QUEUE_MAIN，FIFO）
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 缓存穿透占位符，表示"已查过 DB，确认不存在"，区别于 key 不存在（未查过）。
     */
    public static final String NULL_VALUE = "__NULL__";

    // ─────────────────────────────────────────────────────────────────────────
    // String 操作
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 写入缓存（永久，无 TTL）。
     *
     * <p>仅用于计数器（likeCount、viewCount、userUnreadCount）和持久队列，
     * 其他场景必须指定 TTL，避免内存无限增长。</p>
     */
    public void set(String key, Object value) {
        try {
            redisTemplate.opsForValue().set(key, value);
        } catch (Exception e) {
            log.error("[Redis] set 失败，key={}", key, e);
        }
    }

    /**
     * 写入缓存（指定 TTL）。
     *
     * <p>timeout 为 null / zero / negative 时退化为永久写入，并记录 warn 日志，
     * 便于排查意外的永久 key。</p>
     */
    public void set(String key, Object value, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            log.warn("[Redis] set 调用未指定有效 TTL，将永久写入，key={}，请确认是否符合预期", key);
            set(key, value);
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, value, timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("[Redis] set 失败，key={}", key, e);
        }
    }

    /**
     * 读取纯字符串缓存。
     *
     * <p><b>适用场景</b>：存入时是字符串，如 access_token、点赞状态标记 "1"、
     * 占位符 "__NULL__"、微信 openid 等。</p>
     *
     * <p><b>不适用</b>：存入的是 VO/DTO 对象，此时应使用 {@link #get(String, Class)}。
     * 本方法对非 String 类型一律返回 null，不做 toString 转换，避免返回无意义字符串。</p>
     *
     * @return 字符串值；key 不存在、类型不是 String、Redis 异常均返回 null
     */
    public String getString(String key) {
        Object value = getRaw(key);
        if (value instanceof String str) {
            return str;
        }
        // 存的是对象却调了 getString，属于使用错误，记录 warn 方便排查
        if (value != null) {
            log.warn("[Redis] getString 类型不匹配，key={}, actualType={}", key, value.getClass().getName());
        }
        return null;
    }

    /**
     * 读取结构化对象缓存（支持泛型）。
     *
     * @param key 缓存 key
     * @param typeRef 期望的目标类型，支持泛型，如 new TypeReference<List<PostDetailVO>>() {}
     * @return 反序列化后的对象；未命中或类型不匹配返回 null，业务层统一走 DB 兜底
     * @param <T> 目标类型
     */
    public <T> T get(String key, TypeReference<T> typeRef) {
        Object value = getRaw(key);
        if (value == null) return null;
        try {
            // convertValue: LinkedHashMap → 目标类型，不会再走 Redis IO
            return objectMapper.convertValue(value, typeRef);
        } catch (IllegalArgumentException e) {
            log.warn("[Redis] get 转换失败，key={}, type={}", key, typeRef.getType(), e);
            return null;
        }
    }

    /**
     * 读取结构化对象缓存。
     *
     * <p><b>适用场景</b>：存入时是 VO/DTO，如 PostDetailVO、UserSettingVO、UserSimpleVO。</p>
     *
     * <p>底层依赖 Jackson 反序列化，写入与读取的 Class 必须一致，
     * 字段增减需注意兼容性（建议给新字段加默认值）。</p>
     *
     * @param clazz 期望的目标类型
     * @return 反序列化后的对象；未命中或类型不匹配返回 null，业务层统一走 DB 兜底
     */
    public <T> T get(String key, Class<T> clazz) {
        Object value = getRaw(key);
        if (value == null) {
            return null;
        }
        if (clazz.isInstance(value)) {
            return clazz.cast(value);
        }
        log.warn("[Redis] get 类型不匹配，key={}, expected={}, actual={}",
                key, clazz.getName(), value.getClass().getName());
        return null;
    }

    /**
     * 读取原始对象缓存。
     *
     * <p>用于需要自行判断缓存值类型（如对象或 NULL 占位符）的场景。</p>
     */
    public Object get(String key) {
        return getRaw(key);
    }

    /**
     * 读取计数器值。
     *
     * <p><b>适用场景</b>：likeCount、viewCount、userUnreadCount 等通过
     * {@link #incrementAndRefresh(String, Duration)} /
     * {@link #decrementAndRefresh(String, Duration)} 维护的计数器。</p>
     *
     * @return 计数值；key 不存在或 Redis 异常返回 null，业务层从 DB 冗余字段兜底
     */
    public Long getLong(String key) {
        Object value = getRaw(key);
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Integer i) {
            return i.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        if (value != null) {
            log.warn("[Redis] getLong 类型不匹配，key={}, actualType={}", key, value.getClass().getName());
        }
        return null;
    }

    /**
     * 批量获取对象缓存（multiGet）。
     *
     * <p>一次网络往返获取多个 key 的值，适合列表场景下批量加载关联数据，
     * 如帖子列表的作者信息、评论列表的用户头像等。</p>
     *
     * <p>返回 Map 的 value 为 null 表示该 key 未命中，调用方需对未命中的 key
     * 回源 DB 并调用 {@link #set} 或 {@link #multiSet} 回写缓存。</p>
     *
     * @param keys  key 列表，顺序与返回 Map 的 key 对应
     * @param clazz 期望的值类型
     * @return key → 值的 Map；Redis 异常时返回空 Map，由调用方全量走 DB 兜底
     */
    public <T> Map<String, T> multiGet(List<String> keys, Class<T> clazz) {
        if (keys == null || keys.isEmpty()) return Map.of();
        try {
            List<Object> values = redisTemplate.opsForValue().multiGet(keys);
            Map<String, T> result = new LinkedHashMap<>(keys.size());
            if (values == null) return result;

            for (int i = 0; i < keys.size(); i++) {
                Object val = values.get(i);
                if (clazz.isInstance(val)) {
                    result.put(keys.get(i), clazz.cast(val));
                } else {
                    result.put(keys.get(i), null); // 未命中或类型不匹配，标记 null
                    if (val != null) {
                        log.warn("[Redis] multiGet 类型不匹配，key={}, expected={}, actual={}",
                                keys.get(i), clazz.getName(), val.getClass().getName());
                    }
                }
            }
            return result;
        } catch (Exception e) {
            log.error("[Redis] multiGet 失败，keys={}", keys, e);
            return Map.of(); // 全量降级，调用方走 DB
        }
    }

    /**
     * 批量写入缓存（pipeline，单次网络往返）。
     *
     * <p>配合 {@link #multiGet} 使用，将 DB 回源结果批量回写缓存，
     * 比循环调用 {@link #set} 减少网络往返次数。</p>
     *
     * @param entries key → 值的 Map
     * @param timeout TTL（所有 key 使用相同 TTL）
     */
    public void multiSet(Map<String, Object> entries, Duration timeout) {
        mSet(entries, timeout);
    }

    /**
     * 批量永久写入缓存（pipeline，单次网络往返）。
     *
     * <p>配合 {@link #multiGet} 使用，将 DB 回源结果批量回写缓存，
     * 比循环调用 {@link #set} 减少网络往返次数。</p>
     *
     * @param entries key → 值的 Map
     */
    public void multiSet(Map<String, Object> entries) {
        mSet(entries, null);
    }

    public void mSet(Map<String, Object> entries, Duration timeout) {
        if (entries == null || entries.isEmpty()) return;

        try {
            // 1. 提前获取序列化器并显式强转，解决 capture of ? 的泛型报错，同时提升循环内性能
            RedisSerializer<String> keySerializer = redisTemplate.getStringSerializer();
            @SuppressWarnings("unchecked")
            RedisSerializer<Object> valueSerializer = (RedisSerializer<Object>) redisTemplate.getValueSerializer();

            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                entries.forEach((key, value) -> {
                    try {
                        byte[] rawKey = keySerializer.serialize(key);
                        byte[] rawValue = valueSerializer.serialize(value);

                        if (rawKey != null && rawValue != null) {
                            if (timeout == null) {
                                // 永久写入
                                connection.stringCommands().set(rawKey, rawValue);
                            } else {
                                connection.stringCommands().set(
                                        rawKey,
                                        rawValue,
                                        Expiration.from(timeout),
                                        RedisStringCommands.SetOption.upsert()
                                );
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[Redis] mset pipeline 单条序列化失败，key={}", key, e);
                    }
                });
                return null; // pipeline 模式下 callback 必须返回 null
            });
        } catch (Exception e) {
            log.error("[Redis] mset 失败", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NULL 占位符
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 判断缓存值是否为 NULL 占位符。
     *
     * <p>用于防缓存穿透：先取原始值，再判断是否为占位符，命中则直接抛 NOT_FOUND。</p>
     *
     * <pre>{@code
     * String raw = redisService.getString(key);
     * if (redisService.isNullPlaceholder(raw)) throw new BizException(NOT_FOUND);
     * }</pre>
     */
    public boolean isNullPlaceholder(Object value) {
        return NULL_VALUE.equals(value);
    }

    /**
     * 写入 NULL 占位符（防缓存穿透）。
     *
     * <p>DB 查询结果为 null 时调用，TTL 建议 30s，不宜过长，
     * 否则真实数据发布后仍会命中占位符。</p>
     *
     * <p>适用 key：postDetail、activityDetail、examDetail、targetExists。</p>
     */
    public void setNullPlaceholder(String key, Duration timeout) {
        set(key, NULL_VALUE, timeout);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 计数器操作
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 计数器 +1。
     *
     * <p>适用：点赞数、浏览量、未读通知数的递增。</p>
     *
     * @return 递增后的值；Redis 异常返回 null
     */
    public Long increment(String key) {
        return increment(key, 1);
    }

    /**
     * 计数器 +1，同时重置 TTL。
     *
     * <p>适用于 likeCount / viewCount 等需要"活跃则续期"语义的计数器：
     * 每次有新操作时 TTL 重置为指定时长，确保活跃内容的计数器不会在刷库前过期；
     * 内容不活跃超过 TTL 后自然过期，下次读时从 DB 冗余字段重建。</p>
     *
     * <p>TTL 必须远大于刷库间隔，通常设为刷库间隔的 24 倍以上（如刷库5分钟、TTL2小时），
     * 保证 key 过期时所有增量已被刷入 DB，不存在数据丢失。</p>
     *
     * @param key     计数器 key
     * @param ttl     每次写操作后重置的 TTL
     * @return 递增后的值；Redis 异常返回 null
     */
    public Long incrementAndRefresh(String key, Duration ttl) {
        try {
            // pipeline：incr + expire 两条命令一次发送，减少往返
            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] rawKey = redisTemplate.getStringSerializer().serialize(key);
                if (rawKey != null) {
                    connection.stringCommands().incr(rawKey);
                    connection.keyCommands().expire(rawKey, ttl.getSeconds());
                }
                return null;
            });
            // results[0] = incr 结果
            return !results.isEmpty() ? (Long) results.get(0) : null;
        } catch (Exception e) {
            log.error("[Redis] incrementAndRefresh 失败，key={}", key, e);
            return null;
        }
    }

    /**
     * 计数器 -1。
     *
     * <p>适用：取消点赞、标记已读（单条）时的未读数递减。
     * 注意：Redis 中计数器不设下界，业务层需自行保证不降至负数。</p>
     *
     * @return 递减后的值；Redis 异常返回 null
     */
    public Long decrement(String key) {
        return increment(key, -1);
    }

    /**
     * 计数器 -1，同时重置 TTL。
     *
     * <p>取消点赞时使用，语义同 {@link #incrementAndRefresh}。</p>
     *
     * <p>适用：取消点赞、标记已读（单条）时的未读数递减。
     * 注意：Redis 中计数器不设下界，业务层需自行保证不降至负数。</p>
     *
     * @return 递减后的值；Redis 异常返回 null
     */
    public Long decrementAndRefresh(String key, Duration ttl) {
        try {
            List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                byte[] rawKey = redisTemplate.getStringSerializer().serialize(key);
                if (rawKey != null) {
                    connection.stringCommands().decr(rawKey);
                    connection.keyCommands().expire(rawKey, ttl.getSeconds());
                }
                return null;
            });
            return !results.isEmpty() ? (Long) results.get(0) : null;
        } catch (Exception e) {
            log.error("[Redis] decrementAndRefresh 失败，key={}", key, e);
            return null;
        }
    }

    /**
     * 计数器按指定步长增减。
     *
     * <p>delta 为负数时等效于 decrement。</p>
     *
     * @return 操作后的值；Redis 异常返回 null
     */
    public Long increment(String key, long delta) {
        try {
            return redisTemplate.opsForValue().increment(key, delta);
        } catch (Exception e) {
            log.error("[Redis] increment 失败，key={}, delta={}", key, delta, e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 通用 key 操作
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 删除缓存。
     *
     * <p>写后删（Cache-Aside）的标准步骤：先更新 DB，再调本方法删缓存，
     * 让下次读时重建，避免写-写并发导致缓存与 DB 不一致。</p>
     *
     * @return true 表示成功删除；key 不存在或异常返回 false
     */
    public boolean delete(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        try {
            return redisTemplate.delete(key);
        } catch (Exception e) {
            log.error("[Redis] delete 失败，key={}", key, e);
            return false;
        }
    }

    /**
     * 按前缀扫描 key。效率太低，遇到大量key要花费很多时间与资源
     *
     * <p>用于低频后台任务（如计数回写），避免业务层直接依赖 RedisTemplate。</p>
     */
    public Set<String> scanKeysByPrefix(String prefix) {
        if (!StringUtils.hasText(prefix)) {
            return Set.of();
        }
        try {
            return redisTemplate.execute((RedisCallback<Set<String>>) connection -> {

                Set<String> keys = new HashSet<>();

                ScanOptions options = ScanOptions.scanOptions()
                        .match(prefix + "*")
                        .count(500)
                        .build();

                try (Cursor<byte[]> cursor = connection.scan(options)) {
                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                    }
                }

                return keys;
            });
        } catch (Exception e) {
            log.error("[Redis] scanKeysByPrefix 失败，prefix={}", prefix, e);
            return Set.of();
        }
    }

    /**
     * 仅当 key 不存在时写入（原子操作），常用于分布式锁。
     *
     * <p>典型用法：点赞防并发重复提交。</p>
     *
     * <pre>{@code
     * boolean locked = redisService.setIfAbsent(lockKey, "1", LIKE_COUNT_LOCK_TTL);
     * if (!locked) throw new BizException("操作频繁，请稍后再试");
     * try {
     *     // 业务逻辑
     * } finally {
     *     redisService.delete(lockKey);
     * }
     * }</pre>
     *
     * @return true 表示抢锁成功；false 表示 key 已存在（锁被持有）或异常
     */
    public boolean setIfAbsent(String key, Object value, Duration timeout) {
        if (!StringUtils.hasText(key) || timeout == null || timeout.isZero() || timeout.isNegative()) {
            log.warn("[Redis] setIfAbsent 参数非法，key={}", key);
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, value, timeout));
        } catch (Exception e) {
            log.error("[Redis] setIfAbsent 失败，key={}", key, e);
            return false;
        }
    }

    /**
     * 为已存在的 key 设置过期时间。
     *
     * <p>仅在"先写永久 key，后续再补 TTL"的场景下使用。
     * 通常应在 {@link #set(String, Object, Duration)} 时一并指定 TTL，避免调用本方法。</p>
     */
    public boolean expire(String key, Duration timeout) {
        if (!StringUtils.hasText(key) || timeout == null || timeout.isZero() || timeout.isNegative()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.expire(key, timeout));
        } catch (Exception e) {
            log.error("[Redis] expire 失败，key={}", key, e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ZSet 操作（延迟队列：NOTIFY_DELAY_ZSET）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 向有序集合添加元素（延迟队列入队）。
     *
     * <p>用于 NOTIFY_DELAY_ZSET：score 传推送时间戳（秒），member 传 "nq:{notifyQueueId}"。</p>
     *
     * <pre>{@code
     * redisService.zAdd(NOTIFY_DELAY_ZSET, "nq:" + queueId, sendAt.getEpochSecond());
     * }</pre>
     *
     * @param score  排序分值，延迟队列场景下传 Unix 时间戳（秒）
     * @param member 成员标识
     * @return true 表示新增成功；false 表示已存在（score 会被更新）或异常
     */
    public boolean zAdd(String key, String member, double score) {
        try {
            return Boolean.TRUE.equals(redisTemplate.opsForZSet().add(key, member, score));
        } catch (Exception e) {
            log.error("[Redis] zAdd 失败，key={}, member={}", key, member, e);
            return false;
        }
    }

    /**
     * 为 ZSet 成员增加分数。
     *
     * <p>用于搜索热词这类场景：每次命中就把对应关键词的 score +1，
     * 由 ZSet 自身承担排名维护。</p>
     *
     * @param key   有序集合 key
     * @param member 成员标识
     * @param delta 增量，通常传 1
     * @return 更新后的 score；异常返回 null
     */
    public Double zIncrementScore(String key, String member, double delta) {
        try {
            return redisTemplate.opsForZSet().incrementScore(key, member, delta);
        } catch (Exception e) {
            log.error("[Redis] zIncrementScore 失败，key={}, member={}, delta={}", key, member, delta, e);
            return null;
        }
    }

    /**
     * 按 score 范围取有序集合元素（延迟队列消费）。
     *
     * <p>单线程监听轮询用法：取 score 在 [0, 当前时间戳] 内的到期任务，每次最多取 50 条。</p>
     *
     * <pre>{@code
     * Set<ZSetOperations.TypedTuple<Object>> dues =
     *         redisService.zRangeByScore(NOTIFY_DELAY_ZSET, 0, Instant.now().getEpochSecond(), 0, 50);
     * }</pre>
     *
     * @param min    score 下界（含）
     * @param max    score 上界（含）
     * @param offset 偏移量（分页起始）
     * @param count  最大返回条数
     * @return 元素集合（含 score 信息）；异常返回空集合，不返回 null
     */
    public Set<ZSetOperations.TypedTuple<Object>> zRangeByScore(
            String key, double min, double max, long offset, long count) {
        try {
            Set<ZSetOperations.TypedTuple<Object>> result =
                    redisTemplate.opsForZSet().rangeByScoreWithScores(key, min, max, offset, count);
            return result != null ? result : Set.of();
        } catch (Exception e) {
            log.error("[Redis] zRangeByScore 失败，key={}", key, e);
            return Set.of();
        }
    }

    /**
     * 从有序集合移除指定成员（延迟队列消费确认）。
     *
     * <p>任务处理成功后调用，处理失败则不移除，下次轮询自动重试。</p>
     *
     * @param members 要移除的成员（可批量）
     * @return 实际移除的数量；异常返回 0
     */
    public long zRemove(String key, Object... members) {
        try {
            Long removed = redisTemplate.opsForZSet().remove(key, members);
            return removed != null ? removed : 0L;
        } catch (Exception e) {
            log.error("[Redis] zRemove 失败，key={}", key, e);
            return 0L;
        }
    }

    /**
     * 移除有序集合指定下标范围的元素（按排名从小到大）。
     *
     * <p>配合 zIncrementScore 做 ZSet 瘦身，写入后调一次保留 Top N：</p>
     *
     * <pre>{@code
     * redisService.zRemoveRange(SEARCH_HOT_ZSET, 0, -101); // 只保留 Top 100
     * }</pre>
     *
     * @param start 起始下标（0 为最低分）
     * @param end   结束下标（-1 为最高分，-101 表示第 101 名往后全删）
     * @return 实际移除的数量；异常返回 0
     */
    public long zRemoveRange(String key, long start, long end) {
        try {
            Long removed = redisTemplate.opsForZSet().removeRange(key, start, end);
            return removed != null ? removed : 0L;
        } catch (Exception e) {
            log.error("[Redis] zRemoveRange 失败，key={}", key, e);
            return 0L;
        }
    }

    /**
     * 按 score 从高到低取有序集合元素（不含 score 值）。
     *
     * <p>用于热搜榜展示：取 Top 10 关键词。</p>
     *
     * <pre>{@code
     * Set<Object> hot = redisService.zReverseRange(SEARCH_HOT_ZSET, 0, 9);
     * }</pre>
     *
     * @param start 起始下标
     * @param end   结束下标
     * @return 成员集合（score 高→低）；异常返回空集合
     */
    public Set<Object> zReverseRange(String key, long start, long end) {
        try {
            Set<Object> result = redisTemplate.opsForZSet().reverseRange(key, start, end);
            return result != null ? result : Set.of();
        } catch (Exception e) {
            log.error("[Redis] zReverseRange 失败，key={}", key, e);
            return Set.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // List 操作（普通任务队列：QUEUE_MAIN，FIFO）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 向任务队列尾部入队（生产者）。
     *
     * <p>与 {@link #dequeue} 配合使用，实现 FIFO 语义。
     * 适用于 QUEUE_MAIN 的一般异步任务（审核回调、消息推送等）。</p>
     *
     * @return 入队后队列长度；异常返回 null
     */
    public Long enqueue(String key, Object value) {
        try {
            return redisTemplate.opsForList().rightPush(key, value);
        } catch (Exception e) {
            log.error("[Redis] enqueue 失败，key={}", key, e);
            return null;
        }
    }

    /**
     * 从任务队列头部阻塞出队（消费者）。
     *
     * <p>阻塞等待直到有元素或超时，适合单线程监听循环。
     * {@link RedisConnectionFailureException} 会向上抛出，由调用方决定是否重连。</p>
     *
     * <pre>{@code
     * while (!Thread.currentThread().isInterrupted()) {
     *     Object task = redisService.dequeue(QUEUE_MAIN, 3, TimeUnit.SECONDS);
     *     if (task != null) dispatchExecutor.submit(() -> handle(task));
     * }
     * }</pre>
     *
     * @param timeout 阻塞超时时间（建议 2～5 秒，避免连接长期占用）
     * @param unit    时间单位
     * @return 队列头部元素；超时或异常返回 null
     * @throws RedisConnectionFailureException Redis 连接断开时抛出，调用方需处理重连
     */
    public Object dequeue(String key, long timeout, TimeUnit unit) {
        try {
            return redisTemplate.opsForList().leftPop(key, timeout, unit);
        } catch (RedisConnectionFailureException e) {
            throw e; // 连接异常向上抛，不吞掉，由监听循环决定重连策略
        } catch (Exception e) {
            log.error("[Redis] dequeue 失败，key={}", key, e);
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Set 操作（脏数据集合：LIKE_DIRTY_SET / VIEW_DIRTY_SET）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 向集合添加一个或多个成员（SADD）。
     *
     * <p><b>适用场景</b>：写时登记脏 key，如点赞/浏览计数发生变更时，
     * 将对应的 count key 记入待同步集合，供定时任务按需消费，
     * 替代全量 SCAN 扫描 keyspace。</p>
     *
     * <pre>{@code
     * // 计数写入后登记脏 key，O(1)，不影响主链路
     * redisService.sAdd(RedisKeyConstant.LIKE_DIRTY_SET, countKey);
     * }</pre>
     *
     * @param key     集合 key
     * @param members 要添加的成员（可变参数，支持批量登记）
     * @return 实际新增的成员数量（已存在的不计入）；异常返回 0
     */
    public long sAdd(String key, String... members) {
        if (!StringUtils.hasText(key) || members == null || members.length == 0) {
            return 0L;
        }
        try {
            Long added = redisTemplate.opsForSet().add(key, (Object[]) members);
            return added != null ? added : 0L;
        } catch (Exception e) {
            log.error("[Redis] sAdd 失败，key={}", key, e);
            return 0L;
        }
    }

    /**
     * 获取集合中的所有成员（sMEMBERS）。
     *
     * <p><b>适用场景</b>：定时同步任务取出全部待处理的脏 key，
     * 配合 {@link #delete} 先摘除集合再逐条处理，
     * 确保处理期间新增的脏 key 进入下一轮，不会被遗漏也不会被重复处理。</p>
     *
     * <pre>{@code
     * Set<String> dirtyKeys = redisService.sMembers(RedisKeyConstant.LIKE_DIRTY_SET);
     * if (dirtyKeys.isEmpty()) return 0;
     * redisService.delete(RedisKeyConstant.LIKE_DIRTY_SET); // 先摘，再处理
     * for (String key : dirtyKeys) { ... }
     * }</pre>
     *
     * <p>注意：集合元素量较大时（万级以上），建议改用 SSCAN 分批处理，
     * 当前计数器场景 key 量有限，sMEMBERS 一次性返回即可。</p>
     *
     * @return 集合所有成员；key 不存在或异常返回空集合，不返回 null
     */
    public Set<String> sMembers(String key) {
        if (!StringUtils.hasText(key)) {
            return Set.of();
        }
        try {
            Set<Object> raw = redisTemplate.opsForSet().members(key);
            if (raw == null || raw.isEmpty()) {
                return Set.of();
            }
            Set<String> result = new HashSet<>(raw.size());
            for (Object member : raw) {
                if (member instanceof String s) {
                    result.add(s);
                } else if (member != null) {
                    log.warn("[Redis] sMembers 成员类型非 String，key={}, actualType={}", key, member.getClass().getName());
                }
            }
            return result;
        } catch (Exception e) {
            log.error("[Redis] sMembers 失败，key={}", key, e);
            return Set.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部方法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 读取原始缓存值，不做类型转换。
     *
     * <p>所有 get 类方法的底层，统一收口异常处理与日志。</p>
     */
    private Object getRaw(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.error("[Redis] get 失败，key={}", key, e);
            return null;
        }
    }
}