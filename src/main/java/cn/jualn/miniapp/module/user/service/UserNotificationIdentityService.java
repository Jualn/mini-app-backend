package cn.jualn.miniapp.module.user.service;

import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Account-owned notification identity facts; no notification or provider orchestration. */
@Service
@RequiredArgsConstructor
public class UserNotificationIdentityService {
    private final UserProfileMapper users;
    public record Identity(boolean miniProgram, boolean officialAccount) { }
    public Identity identity(long userId) {
        return new Identity(StringUtils.hasText(users.selectMiniOpenid(userId)),
                StringUtils.hasText(users.selectMpOpenIdById(userId)));
    }
    public Set<Long> miniProgramUsers(Collection<Long> ids) {
        if (ids.isEmpty()) return Set.of();
        return new HashSet<>(users.selectMiniProgramUserIds(ids));
    }
    public Set<Long> officialAccountUsers(Collection<Long> userIds) {
        if (userIds.isEmpty()) return Set.of();
        var ids = users.selectOfficialAccountUserIds(userIds);
        return ids == null ? Set.of() : new HashSet<>(ids);
    }
}
