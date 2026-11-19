package cn.jualn.miniapp.module.notify.service.capability;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.user.service.UserNotificationIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import cn.jualn.miniapp.module.notify.bo.NotificationCapabilitiesBO;

@Service
@RequiredArgsConstructor
public class NotificationCapabilityQueries {
    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<CapabilityType> CAPABILITY_TYPES = List.of(
            new CapabilityType(NotificationCategory.ACTIVITY, NotifyType.ACTIVITY_START_REMINDER),
            new CapabilityType(NotificationCategory.ACTIVITY, NotifyType.ACTIVITY_REGISTRATION_START_REMINDER),
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


    private final UserNotificationIdentityService userIdentities;
    private final cn.jualn.miniapp.module.notify.channel.NotificationChannels channels;

    public NotificationCapabilitiesBO getCapabilities() {
        long userId = requireUserId();
        var identity = userIdentities.identity(userId);
        boolean hasMiniProgramIdentity = identity.miniProgram();
        boolean hasOfficialAccountIdentity = identity.officialAccount();
        List<NotificationCapabilitiesBO.Item> items = new ArrayList<>(36);
        for (CapabilityType type : CAPABILITY_TYPES) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                boolean inApp = channel == NotificationChannel.IN_APP;
                boolean officialConfigured = channel == NotificationChannel.WECHAT_OFFICIAL_ACCOUNT
                        && channels.available(NotificationChannel.WECHAT_OFFICIAL_ACCOUNT.name(),type.type());
                boolean miniConfigured = channel == NotificationChannel.WECHAT_MINI_PROGRAM
                        && channels.configured(NotificationChannel.WECHAT_MINI_PROGRAM.name(),type.type());
                boolean available = inApp || officialConfigured || miniConfigured;
                List<String> unavailableReasons = new ArrayList<>(capabilityReasons(channel, available,
                        hasMiniProgramIdentity, hasOfficialAccountIdentity));
                items.add(new NotificationCapabilitiesBO.Item(
                        type.category().name(), type.type().name(), channel.name(), available,
                        inApp ? "NOT_REQUIRED" : officialConfigured || miniConfigured ? "PROVIDER_VERIFIED_AT_SEND" : "UNKNOWN",
                        unavailableReasons));
            }
        }
        return new NotificationCapabilitiesBO(OffsetDateTime.now(APP_ZONE), items);
    }

    private List<String> capabilityReasons(NotificationChannel channel,
                                           boolean configured,
                                           boolean hasMiniProgramIdentity,
                                           boolean hasOfficialAccountIdentity) {
        if (channel == NotificationChannel.IN_APP) return List.of();
        List<String> reasons = new ArrayList<>();
        if (!channels.enabled(channel.name())) reasons.add("CHANNEL_DISABLED");
        else if (!configured) reasons.add("NOT_CONFIGURED");
        if (channel == NotificationChannel.WECHAT_MINI_PROGRAM && !hasMiniProgramIdentity) {
            reasons.add("IDENTITY_REQUIRED");
        }
        if (channel == NotificationChannel.WECHAT_OFFICIAL_ACCOUNT && !hasOfficialAccountIdentity) {
            reasons.add("IDENTITY_REQUIRED");
        }
        return List.copyOf(reasons);
    }

    private long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录态已过期");
        return userId;
    }
    private record CapabilityType(NotificationCategory category, NotifyType type) {}
}
