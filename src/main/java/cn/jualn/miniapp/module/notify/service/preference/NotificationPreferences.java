package cn.jualn.miniapp.module.notify.service.preference;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.notify.bo.NotificationPreferencesBO;
import cn.jualn.miniapp.module.notify.mapper.NotificationPreferenceMapper;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NotificationPreferences {
    private final NotificationPreferenceMapper preferenceMapper;

    public NotificationPreferencesBO getPreferences() {
        try { return preferences(user()); }
        catch (DataAccessException unavailable) { throw ContractProblemException.notificationPreferencesUnavailable(); }
    }

    @Transactional(rollbackFor = Exception.class)
    public NotificationPreferencesBO updatePreferences(NotificationPreferencesBO.Patch request) {
        long userId = user();
        if (request == null || request.changes() == null || request.changes().isEmpty() || request.changes().size() > 27)
            throw invalid("/changes", "SIZE");
        Set<String> seen = new HashSet<>();
        for (var change : request.changes()) {
            if (change == null || change.category() == null || change.channel() == null) throw invalid("/changes", "REQUIRED");
            if (!seen.add(change.category()+":"+change.channel())) throw invalid("/changes", "DUPLICATE_KEY");
        }
        try {
            // Stable row-lock order, without replacing the user's other combinations.
            for (var change : request.changes().stream().sorted(Comparator.comparing(c -> c.category()+":"+c.channel())).toList()) {
                if (change.enabled() == null) preferenceMapper.delete(userId, change.category().name(), change.channel().name());
                else preferenceMapper.upsert(userId, change.category().name(), change.channel().name(), change.enabled(), "USER_OVERRIDE");
            }
            return preferences(userId);
        } catch (DataAccessException unavailable) { throw ContractProblemException.notificationPreferencesUnavailable(); }
    }

    private NotificationPreferencesBO preferences(long userId) {
        Map<String,Boolean> overrides = new HashMap<>();
        for (var row : preferenceMapper.selectByUserId(userId)) overrides.put(row.getCategory()+":"+row.getChannel(), row.getEnabled());
        var items = new ArrayList<NotificationPreferencesBO.Item>(27);
        for (var category : NotificationCategory.values()) for (var channel : NotificationChannel.values()) {
            Boolean value = overrides.get(category+":"+channel);
            items.add(new NotificationPreferencesBO.Item(category.name(), channel.name(),
                    value == null ? channel == NotificationChannel.IN_APP : value, value == null ? "SYSTEM_DEFAULT" : "USER_OVERRIDE"));
        }
        return new NotificationPreferencesBO("1", List.copyOf(items));
    }
    private long user() {
        Long id = UserContext.getUserId();
        if (id == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
        return id;
    }
    private ContractProblemException invalid(String field,String code) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body", field, code, "通知偏好请求不合法"));
    }
}
