package cn.jualn.miniapp.common.constant;

import java.time.Duration;

/**
 * Redis Key 统一管理。
 * <p>所有业务代码应通过本类生成 key，避免硬编码和前缀漂移。</p>
 */
public final class RedisKeyConstant {
    private RedisKeyConstant() {
    }

    // 原本前缀为 "miniapp:" 但节省点redis内存，且目前没有其他业务线，所以直接空前缀，后续如果有需要再加
    public static final String PREFIX = "";

    // ── 用户 ──────────────────────────────────────────────
    public static final String USER_TOKEN_PREFIX = PREFIX + "user:token:";
    public static final Duration USER_TOKEN_TTL = Duration.ofHours(2);

    public static final String USER_PUBLIC_PROFILE_PREFIX = PREFIX + "user:profile:public:";
    public static final Duration USER_PUBLIC_PROFILE_TTL = Duration.ofMinutes(10);

    public static final String USER_SIMPLE_PROFILE_PREFIX = PREFIX + "user:profile:simple:";
    public static final Duration USER_SIMPLE_PROFILE_TTL = Duration.ofMinutes(10);

    public static final String USER_AUTH_PROFILE_PREFIX = PREFIX + "user:profile:auth:";
    public static final Duration USER_AUTH_PROFILE_TTL = Duration.ofMinutes(10);

    public static final String USER_AGREEMENT_PREFIX = PREFIX + "user:agreement:";
    public static final Duration USER_AGREEMENT_TTL = Duration.ofHours(24);  // 协议升级后要能强制重签

    public static final String USER_SETTING_PREFIX = PREFIX + "user:setting:";
    public static final Duration USER_SETTING_TTL = Duration.ofMinutes(30);

    // 无 TTL：主动维护（收到通知+1，已读清零），不依赖过期
    public static final String USER_UNREAD_COUNT_PREFIX = PREFIX + "user:unread:v2:";

    // ── 内容详情 ──────────────────────────────────────────
    public static final String POST_DETAIL_PREFIX = PREFIX + "post:detail:";
    public static final Duration POST_DETAIL_TTL = Duration.ofMinutes(5);

    public static final String ACTIVITY_DETAIL_PREFIX = PREFIX + "activity:detail:";
    public static final Duration ACTIVITY_DETAIL_TTL = Duration.ofMinutes(5);

    public static final String EXAM_DETAIL_PREFIX = PREFIX + "exam:detail:";
    public static final Duration EXAM_DETAIL_TTL = Duration.ofMinutes(5);

    // ── 互动计数（2小时，定时刷 DB，重启后从 DB 重建，每次写操作重置）
    public static final String LIKE_COUNT_PREFIX = PREFIX + "like:count:";
    public static final Duration LIKE_COUNT_TTL = Duration.ofHours(2);
    // 点赞脏数据集合，用于存储藏数据的 key
    public static final String LIKE_DIRTY_SET = PREFIX + "like:dirty:set";

    public static final String LIKE_STATUS_PREFIX = PREFIX + "like:status:";
    public static final Duration LIKE_STATUS_TTL = Duration.ofMinutes(30);

    public static final String LIKE_COUNT_LOCK_PREFIX = PREFIX + "like:count:lock:";
    public static final Duration LIKE_COUNT_LOCK_TTL = Duration.ofSeconds(3);

    // ── 浏览增量计数 ──────────────────────────────────────────
    // Redis 不再保存 view 总数，只保存“尚未同步到 DB 的增量”。
    // 正常每 5 分钟刷库，TTL 设长一点用于异常兜底，避免同步任务短暂失败导致 key 提前过期。
    public static final String VIEW_DELTA_PREFIX = PREFIX + "view:delta:";
    public static final Duration VIEW_DELTA_TTL = Duration.ofDays(2);
    // 浏览脏数据集合，存储 view_delta key
    public static final String VIEW_DIRTY_SET = PREFIX + "view:dirty:set";

    // ── 存在性检查 ────────────────────────────────────────
    public static final String TARGET_EXIST_PREFIX = PREFIX + "target:exist:";
    public static final Duration TARGET_EXIST_TTL = Duration.ofMinutes(10);

    // ── 订阅状态 ──────────────────────────────────────────
    public static final String ACTIVITY_ENROLL_PREFIX = PREFIX + "activity:enroll:";
    public static final Duration ACTIVITY_ENROLL_TTL = Duration.ofMinutes(30);

    public static final String EXAM_SUBSCRIPTION_PREFIX = PREFIX + "exam:subscription:";
    public static final Duration EXAM_SUBSCRIPTION_TTL = Duration.ofMinutes(30);

    // ── 搜素 ──────────────────────────────────────────
    public static final String SEARCH_COUNT_PREFIX = PREFIX + "search:count:";
    public static final Duration SEARCH_COUNT_TTL = Duration.ofMinutes(5);

    public static final String SEARCH_HOT_ZSET = PREFIX + "search:hot:set";
    public static final String SEARCH_HOT_DEDUP = PREFIX + "search:dedup:";
    public static final Duration SEARCH_HOT_DEDUP_TTL = Duration.ofMinutes(10);

    // ── 微信 ──────────────────────────────────────────────
    private static final String WX_CREDENTIAL_PREFIX = PREFIX + "wx:credential:";

    // 用于 OAuth 防伪和定位 userId
    public static final String WX_MP_OAUTH_STATUS_PREFIX = PREFIX + "wx:mp:oauth:status";
    public static final Duration WX_MP_OAUTH_STATUS_TTL = Duration.ofMinutes(10);  // 微信扫码登录状态，短效

    // 用于 H5 回传订阅结果时定位 userId
    public static final String WX_MP_SUBSCRIBE_STATUS_PREFIX = PREFIX + "wx:mp:subscribe:status";
    public static final Duration WX_MP_SUBSCRIBE_STATUS_TTL = Duration.ofMinutes(10);  // 微信扫码登录时的关注状态，短效

    public static final String WX_BIND_SCENE_PREFIX = PREFIX + "wx:bind:scene:";
    public static final Duration WX_BIND_SCENE_TTL = Duration.ofMinutes(5);   // 扫码绑定场景值，短效

    public static final String WX_AUDIT_TRACE_PREFIX = PREFIX + "wx:audit:trace:";
    public static final Duration WX_AUDIT_TRACE_TTL = Duration.ofHours(1);

    public static final String WX_SUBSCRIBE_STATUS_PREFIX = PREFIX + "wx:subscribe:";
    public static final Duration WX_SUBSCRIBE_STATUS_TTL = Duration.ofHours(1);

    // ── Key 构造方法 ───────────────────────────────────────
    public static String userToken(Long userId) {
        return USER_TOKEN_PREFIX + userId;
    }

    public static String userPublicProfile(Long userId) {
        return USER_PUBLIC_PROFILE_PREFIX + userId;
    }

    public static String userSimpleProfile(Long userId) {
        return USER_SIMPLE_PROFILE_PREFIX + userId;
    }

    public static String userAuthProfile(Long userId) {
        return USER_AUTH_PROFILE_PREFIX + userId;
    }

    public static String userAgreement(Long userId) {
        return USER_AGREEMENT_PREFIX + userId;
    }

    public static String userSetting(Long userId) {
        return USER_SETTING_PREFIX + userId;
    }

    public static String userUnreadCount(Long userId) {
        return USER_UNREAD_COUNT_PREFIX + userId;
    }

    public static String postDetail(Long postId) {
        return POST_DETAIL_PREFIX + postId;
    }

    public static String activityDetail(Long activityId) {
        return ACTIVITY_DETAIL_PREFIX + activityId;
    }

    public static String examDetail(Long examId) {
        return EXAM_DETAIL_PREFIX + examId;
    }

    public static String likeCount(String targetType, Long targetId) {
        return LIKE_COUNT_PREFIX + targetType + ":" + targetId;
    }

    public static String likeStatus(Long userId, String targetType, Long targetId) {
        return LIKE_STATUS_PREFIX + userId + ":" + targetType + ":" + targetId;
    }

    public static String likeCountLock(String targetType, Long targetId) {
        return LIKE_COUNT_LOCK_PREFIX + targetType + ":" + targetId;
    }

    public static String viewDelta(String targetType, Long targetId) {
        return VIEW_DELTA_PREFIX + targetType + ":" + targetId;
    }

    public static String targetExists(String targetType, Long targetId) {
        return TARGET_EXIST_PREFIX + targetType + ":" + targetId;
    }

    public static String activityEnrollment(Long activityId, Long userId) {
        return ACTIVITY_ENROLL_PREFIX + activityId + ":" + userId;
    }

    public static String examSubscription(Long examId, Long userId) {
        return EXAM_SUBSCRIPTION_PREFIX + examId + ":" + userId;
    }

    public static String searchCount(String keyword) {
        return SEARCH_COUNT_PREFIX + keyword;
    }

    public static String searchHotDedup(Long userId, String keyword) {
        return SEARCH_HOT_DEDUP + userId + ":" + keyword;
    }

    public static String wxBindScene(String scene) {
        return WX_BIND_SCENE_PREFIX + scene;
    }

    public static String wxAuditTrace(String traceId) {
        return WX_AUDIT_TRACE_PREFIX + traceId;
    }

    public static String wxSubscribeStatus(Long userId, Integer type) {
        return WX_SUBSCRIBE_STATUS_PREFIX + userId + ":" + type;
    }

    public static String wxMpOauthState(String state) {
        return WX_MP_OAUTH_STATUS_PREFIX + state;
    }

    public static String wxMpOauthClaim(String state) {
        return wxMpOauthState(state) + ":claim";
    }

    public static String wxMpSubscribeState(String state) {
        return WX_MP_SUBSCRIBE_STATUS_PREFIX + state;
    }

    public static String wxAccessToken(String accountType, String appId) {
        return WX_CREDENTIAL_PREFIX + accountType + ":" + appId + ":access-token";
    }

    public static String adminQrSession(String id) { return "jualn:admin:qr-login:v2:session:" + id; }
    public static String adminQrScene(String hash) { return "jualn:admin:qr-login:v2:scene:" + hash; }
    public static String adminQrImage(String id) { return "jualn:admin:qr-login:v2:image:" + id; }
    public static String adminQrActivation(String id) { return "jualn:admin:qr-login:v2:activation:" + id; }
    public static String adminQrRate(String operation, String hash) {
        return "jualn:admin:auth:rate:v2:" + operation + ":" + hash;
    }
    public static String adminRevokedToken(String hash) { return "jualn:admin:auth:revoked:" + hash; }

    public static String wxJsApiTicket(String appId) {
        return WX_CREDENTIAL_PREFIX + "mp:" + appId + ":jsapi-ticket";
    }

}
