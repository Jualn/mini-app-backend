package cn.jualn.miniapp.module.user.audit;

import cn.jualn.miniapp.common.annotation.AuditTarget;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AuditTarget(AuditScene.USER_AVATAR)
public class UserAvatarAuditCallback extends AbstractUserProfileAuditCallback {

    public UserAvatarAuditCallback(
            UserProfileMapper userProfileMapper,
            RedisService redisService,
            NotifyService notifyService
    ) {
        super(userProfileMapper, redisService, notifyService);
    }

    @Override
    public void onReject(Long userId, Long auditLogId, String reason) {
        // Switch boundary: old and current audit results never mutate effective profiles.
        // AuditResultPersistenceService already retains the moderation fact.
    }
}
