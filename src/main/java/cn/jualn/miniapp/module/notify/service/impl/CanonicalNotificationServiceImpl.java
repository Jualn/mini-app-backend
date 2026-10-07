package cn.jualn.miniapp.module.notify.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.NotifyType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.dto.request.CanonicalNotificationQuery;
import cn.jualn.miniapp.module.notify.dto.request.UpdateNotificationPreferencesRequest;
import cn.jualn.miniapp.module.notify.entity.Notification;
import cn.jualn.miniapp.module.notify.entity.NotificationPreference;
import cn.jualn.miniapp.module.notify.mapper.NotificationMapper;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.notify.service.CanonicalNotificationService;
import cn.jualn.miniapp.module.notify.service.NotificationCursorCodec;
import cn.jualn.miniapp.module.notify.service.NotificationPreferenceBridgeService;
import cn.jualn.miniapp.module.notify.vo.CanonicalNotificationPageVO;
import cn.jualn.miniapp.module.notify.vo.NotificationChannelCapabilitiesVO;
import cn.jualn.miniapp.module.notify.vo.NotificationPreferencesVO;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import cn.jualn.miniapp.module.wx.service.WxMpNoticeSendService;
import cn.jualn.miniapp.third.wx.notice.WxMpNoticeType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CanonicalNotificationServiceImpl implements CanonicalNotificationService {
    private static final String DEFAULT_VERSION = "1";
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<CapabilityType> CAPABILITY_TYPES = List.of(
            new CapabilityType(NotificationCategory.ACTIVITY, NotifyType.ACTIVITY_START_REMINDER),
            new CapabilityType(NotificationCategory.ACTIVITY, NotifyType.ACTIVITY_REGISTRATION_DEADLINE_REMINDER),
            new CapabilityType(NotificationCategory.PUBLIC_EVENT, NotifyType.PUBLIC_EVENT_START_REMINDER),
            new CapabilityType(NotificationCategory.PUBLIC_EVENT, NotifyType.PUBLIC_EVENT_REGISTRATION_DEADLINE_REMINDER),
            new CapabilityType(NotificationCategory.ACTIVITY, NotifyType.ACTIVITY_CANCELLED),
            new CapabilityType(NotificationCategory.ACTIVITY, NotifyType.ACTIVITY_TIME_CHANGED),
            new CapabilityType(NotificationCategory.ACTIVITY, NotifyType.ACTIVITY_LOCATION_CHANGED),
            new CapabilityType(NotificationCategory.ACTIVITY, NotifyType.ACTIVITY_ENDED_EARLY),
            new CapabilityType(NotificationCategory.PUBLIC_EVENT, NotifyType.PUBLIC_EVENT_CANCELLED),
            new CapabilityType(NotificationCategory.PUBLIC_EVENT, NotifyType.PUBLIC_EVENT_TIME_CHANGED),
            new CapabilityType(NotificationCategory.PUBLIC_EVENT, NotifyType.PUBLIC_EVENT_LOCATION_CHANGED));

    private final NotificationPreferenceMapper preferenceMapper;
    private final NotificationMapper notificationMapper;
    private final RedisService redisService;
    private final NotificationPreferenceBridgeService preferenceBridgeService;
    private final UserProfileMapper userProfileMapper;
    private final WxMpNoticeSendService wxMpNoticeSendService;

    @Override
    public NotificationPreferencesVO getPreferences() {
        long userId = requireUserId();
        requirePreferenceOwner(userId);
        return preferences(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationPreferencesVO updatePreferences(UpdateNotificationPreferencesRequest request) {
        long userId = requireUserId();
        requirePreferenceOwner(userId);
        validatePatch(request);
        boolean officialAccountChanged = false;
        for (UpdateNotificationPreferencesRequest.Change change : request.getChanges()) {
            officialAccountChanged |= change.getChannel() == NotificationChannel.WECHAT_OFFICIAL_ACCOUNT;
            if (change.getEnabled() == null) {
                preferenceMapper.delete(userId, change.getCategory().name(), change.getChannel().name());
            } else {
                preferenceMapper.upsert(userId, change.getCategory().name(), change.getChannel().name(),
                        change.getEnabled(), "USER_OVERRIDE");
            }
        }
        if (officialAccountChanged) invalidateAfterCommit(RedisKeyConstant.userSetting(userId));
        return preferences(userId);
    }

    @Override
    public NotificationChannelCapabilitiesVO getCapabilities() {
        long userId = requireUserId();
        boolean hasMiniProgramIdentity = StringUtils.hasText(userProfileMapper.selectMiniOpenid(userId));
        boolean hasOfficialAccountIdentity = StringUtils.hasText(userProfileMapper.selectMpOpenIdById(userId));
        List<NotificationChannelCapabilitiesVO.Item> items = new ArrayList<>(33);
        for (CapabilityType type : CAPABILITY_TYPES) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                boolean inApp = channel == NotificationChannel.IN_APP;
                boolean officialActivityStart = channel == NotificationChannel.WECHAT_OFFICIAL_ACCOUNT
                        && type.type() == NotifyType.ACTIVITY_START_REMINDER
                        && wxMpNoticeSendService.isNotificationDeliveryAvailable(WxMpNoticeType.ACTIVITY_START);
                List<String> unavailableReasons = capabilityReasons(channel, officialActivityStart,
                        hasMiniProgramIdentity, hasOfficialAccountIdentity);
                boolean available = inApp || officialActivityStart;
                items.add(new NotificationChannelCapabilitiesVO.Item(
                        type.category().name(), type.type().name(), channel.name(), available,
                        inApp ? "NOT_REQUIRED" : officialActivityStart ? "PROVIDER_VERIFIED_AT_SEND" : "UNKNOWN",
                        unavailableReasons));
            }
        }
        return new NotificationChannelCapabilitiesVO(OffsetDateTime.now(APP_ZONE).toString(), items);
    }

    private List<String> capabilityReasons(NotificationChannel channel,
                                           boolean officialActivityStart,
                                           boolean hasMiniProgramIdentity,
                                           boolean hasOfficialAccountIdentity) {
        if (channel == NotificationChannel.IN_APP) return List.of();
        List<String> reasons = new ArrayList<>();
        if (!officialActivityStart) reasons.add("NOT_CONFIGURED");
        if (channel == NotificationChannel.WECHAT_MINI_PROGRAM && !hasMiniProgramIdentity) {
            reasons.add("IDENTITY_REQUIRED");
        }
        if (channel == NotificationChannel.WECHAT_OFFICIAL_ACCOUNT && !hasOfficialAccountIdentity) {
            reasons.add("IDENTITY_REQUIRED");
        }
        return List.copyOf(reasons);
    }

    @Override
    public CanonicalNotificationPageVO list(CanonicalNotificationQuery query) {
        long userId = requireUserId();
        CanonicalNotificationQuery actual = query == null ? new CanonicalNotificationQuery() : query;
        int pageSize = actual.getPageSize() == null ? 20 : actual.getPageSize();
        NotificationCursorCodec.Position cursor = NotificationCursorCodec.decode(userId, actual);
        List<Notification> rows = notificationMapper.selectCanonicalInbox(userId,
                cursor == null ? null : cursor.createdAt(), cursor == null ? null : cursor.id(),
                typeCodes(actual.getCategory()), actual.getIsRead(), pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Notification> page = hasMore ? rows.subList(0, pageSize) : rows;
        List<CanonicalNotificationPageVO.Item> items = page.stream().map(this::toItem).toList();
        Notification last = page.isEmpty() ? null : page.get(page.size() - 1);
        String nextCursor = hasMore
                ? NotificationCursorCodec.encode(userId, actual, last.getCreatedAt(), last.getId())
                : null;
        return new CanonicalNotificationPageVO(items, nextCursor);
    }

    @Override
    public long unreadCount() {
        long userId = requireUserId();
        Long count = notificationMapper.countCanonicalUnread(userId);
        return count == null ? 0L : count;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(String notificationId) {
        long userId = requireUserId();
        long id = resourceId(notificationId);
        notificationMapper.ensureInboxCounter(userId);
        notificationMapper.lockInboxHead(userId);
        Integer state = notificationMapper.selectCanonicalReadState(id, userId);
        if (state == null) throw notFound();
        if (state == 0) {
            notificationMapper.markCanonicalRead(id, userId);
            notificationMapper.advanceReadVersion(userId);
            invalidateLegacyUnreadAfterCommit(userId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markAllRead() {
        long userId = requireUserId();
        notificationMapper.ensureInboxCounter(userId);
        notificationMapper.lockInboxHead(userId);
        notificationMapper.markAllCanonicalRead(userId);
        notificationMapper.advanceReadVersion(userId);
        invalidateLegacyUnreadAfterCommit(userId);
    }

    private NotificationPreferencesVO preferences(long userId) {
        Map<String, NotificationPreference> overrides = new HashMap<>();
        for (NotificationPreference preference : preferenceMapper.selectByUserId(userId)) {
            overrides.put(key(preference.getCategory(), preference.getChannel()), preference);
        }
        List<NotificationPreferencesVO.Item> items = new ArrayList<>(6);
        for (NotificationCategory category : NotificationCategory.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                NotificationPreference override = overrides.get(key(category.name(), channel.name()));
                boolean enabled = override != null ? Boolean.TRUE.equals(override.getEnabled())
                        : channel == NotificationChannel.IN_APP;
                items.add(new NotificationPreferencesVO.Item(category.name(), channel.name(), enabled,
                        override == null ? "SYSTEM_DEFAULT" : override.getSource()));
            }
        }
        return new NotificationPreferencesVO(DEFAULT_VERSION, items);
    }

    private void validatePatch(UpdateNotificationPreferencesRequest request) {
        if (request == null) throw validation("/", "REQUIRED", "请求体不能为空");
        if (!request.getUnknownProperties().isEmpty()) {
            String name = request.getUnknownProperties().keySet().iterator().next();
            throw validation("/" + name, "UNKNOWN_PROPERTY", "不支持的字段");
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < request.getChanges().size(); index++) {
            UpdateNotificationPreferencesRequest.Change change = request.getChanges().get(index);
            if (change == null || change.getCategory() == null || change.getChannel() == null) {
                throw validation("/changes/" + index, "REQUIRED", "category 与 channel 不能为空");
            }
            if (!change.getUnknownProperties().isEmpty()) {
                String name = change.getUnknownProperties().keySet().iterator().next();
                throw validation("/changes/" + index + "/" + name, "UNKNOWN_PROPERTY", "不支持的字段");
            }
            if (!change.isEnabledPresent()) {
                throw validation("/changes/" + index + "/enabled", "REQUIRED", "缺少 enabled");
            }
            String key = key(change.getCategory().name(), change.getChannel().name());
            if (!seen.add(key)) {
                throw validation("/changes/" + index, "DUPLICATE_KEY", "同一类别与渠道只能提交一次");
            }
        }
    }

    private ContractProblemException validation(String pointer, String code, String detail) {
        return ContractProblemException.validation(
                new ContractProblemException.Violation("body", pointer, code, detail));
    }

    private CanonicalNotificationPageVO.Item toItem(Notification row) {
        NotifyType type = NotifyType.fromCode(row.getType());
        String typeName = type == null ? "UNKNOWN_" + row.getType() : type.legacyRepresentation().name();
        TargetType targetType = TargetType.fromCode(row.getTargetType());
        CanonicalNotificationPageVO.Target target = targetType == null || row.getTargetId() == null ? null
                : new CanonicalNotificationPageVO.Target(canonicalTargetType(targetType), row.getTargetId().toString());
        return new CanonicalNotificationPageVO.Item(row.getId().toString(), typeName,
                categoryOf(row.getType()), row.getTitle(), row.getContent(), target,
                row.getReadAt() != null,
                row.getCreatedAt().atZone(APP_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    private String canonicalTargetType(TargetType type) {
        return switch (type) {
            case EXAM -> "PUBLIC_EVENT";
            default -> type.name();
        };
    }

    private String categoryOf(Integer type) {
        if (typeCodes(NotificationCategory.ACTIVITY).contains(type)) return NotificationCategory.ACTIVITY.name();
        if (typeCodes(NotificationCategory.PUBLIC_EVENT).contains(type)) return NotificationCategory.PUBLIC_EVENT.name();
        return null;
    }

    private List<Integer> typeCodes(NotificationCategory category) {
        if (category == null) return null;
        return switch (category) {
            case ACTIVITY -> List.of(4, 8, 9, 12, 13, 14, 15);
            case PUBLIC_EVENT -> List.of(5, 10, 11, 16, 17, 18);
        };
    }

    private long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录态已过期");
        return userId;
    }

    private void requirePreferenceOwner(long userId) {
        if (!preferenceBridgeService.isCanonicalOwner(userId)) {
            throw ContractProblemException.notificationPreferencesUnavailable();
        }
    }

    private long resourceId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (RuntimeException invalid) {
            throw ContractProblemException.validation(new ContractProblemException.Violation(
                    "path", "notificationId", "FORMAT", "notificationId 无效"));
        }
    }

    private ContractProblemException notFound() {
        return new ContractProblemException(HttpStatus.NOT_FOUND, "/problems/not-found", "资源不存在");
    }

    private void invalidateLegacyUnreadAfterCommit(long userId) {
        invalidateAfterCommit(RedisKeyConstant.userUnreadCount(userId));
    }

    private void invalidateAfterCommit(String key) {
        Runnable invalidate = () -> redisService.delete(key);
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { invalidate.run(); }
            });
        } else {
            invalidate.run();
        }
    }

    private String key(String category, String channel) {
        return category + ":" + channel;
    }

    private record CapabilityType(NotificationCategory category, NotifyType type) {}
}
