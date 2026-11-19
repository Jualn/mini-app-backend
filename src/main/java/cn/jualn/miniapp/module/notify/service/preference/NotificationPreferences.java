package cn.jualn.miniapp.module.notify.service.preference;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.entity.NotificationPreference;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.jualn.miniapp.module.notify.bo.NotificationPreferencesBO;

@Service
@RequiredArgsConstructor
public class NotificationPreferences {
    private static final String DEFAULT_VERSION = "1";
    private final NotificationPreferenceMapper preferenceMapper;
    private final RedisService redisService;
    private final NotificationPreferenceBridgeService preferenceBridgeService;

    public NotificationPreferencesBO getPreferences() {
        long userId = requireUserId();
        requirePreferenceOwner(userId);
        return preferences(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public NotificationPreferencesBO updatePreferences(NotificationPreferencesBO.Patch request) {
        long userId = requireUserId();
        requirePreferenceOwner(userId);
        validatePatch(request);
        boolean officialAccountChanged = false;
        for (NotificationPreferencesBO.Change change : request.changes()) {
            officialAccountChanged |= change.channel() == NotificationChannel.WECHAT_OFFICIAL_ACCOUNT;
            if (change.enabled() == null) {
                preferenceMapper.delete(userId, change.category().name(), change.channel().name());
            } else {
                preferenceMapper.upsert(userId, change.category().name(), change.channel().name(),
                        change.enabled(), "USER_OVERRIDE");
            }
        }
        if (officialAccountChanged) invalidateAfterCommit(RedisKeyConstant.userSetting(userId));
        return preferences(userId);
    }

    private NotificationPreferencesBO preferences(long userId) {
        Map<String, NotificationPreference> overrides = new HashMap<>();
        for (NotificationPreference preference : preferenceMapper.selectByUserId(userId)) {
            overrides.put(key(preference.getCategory(), preference.getChannel()), preference);
        }
        List<NotificationPreferencesBO.Item> items = new ArrayList<>(6);
        for (NotificationCategory category : NotificationCategory.values()) {
            for (NotificationChannel channel : NotificationChannel.values()) {
                NotificationPreference override = overrides.get(key(category.name(), channel.name()));
                boolean enabled = override != null ? Boolean.TRUE.equals(override.getEnabled())
                        : channel == NotificationChannel.IN_APP;
                items.add(new NotificationPreferencesBO.Item(category.name(), channel.name(), enabled,
                        override == null ? "SYSTEM_DEFAULT" : override.getSource()));
            }
        }
        return new NotificationPreferencesBO(DEFAULT_VERSION, items);
    }

    private void validatePatch(NotificationPreferencesBO.Patch request) {
        if (request == null) throw validation("/", "REQUIRED", "请求体不能为空");
        if (request.changes() == null || request.changes().isEmpty() || request.changes().size() > 6)
            throw validation("/changes", "SIZE", "changes 必须包含 1 到 6 项");
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < request.changes().size(); index++) {
            NotificationPreferencesBO.Change change = request.changes().get(index);
            if (change == null || change.category() == null || change.channel() == null) {
                throw validation("/changes/" + index, "REQUIRED", "category 与 channel 不能为空");
            }
            String key = key(change.category().name(), change.channel().name());
            if (!seen.add(key)) {
                throw validation("/changes/" + index, "DUPLICATE_KEY", "同一类别与渠道只能提交一次");
            }
        }
    }

    private ContractProblemException validation(String pointer, String code, String detail) {
        return ContractProblemException.validation(
                new ContractProblemException.Violation("body", pointer, code, detail));
    }

    private String key(String category, String channel) {
        return category + ":" + channel;
    }

    private void requirePreferenceOwner(long userId) {
        if (!preferenceBridgeService.isCanonicalOwner(userId)) {
            throw ContractProblemException.notificationPreferencesUnavailable();
        }
    }

    private long requireUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或登录态已过期");
        return userId;
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
}
