package cn.jualn.miniapp.module.user.audit;

import cn.jualn.miniapp.common.annotation.AuditTarget;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueProducer;
import cn.jualn.miniapp.module.user.entity.UserProfile;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AuditTarget(AuditScene.USER_AVATAR)
public class UserAvatarAuditCallback extends AbstractUserProfileAuditCallback {

    public UserAvatarAuditCallback(
            UserProfileMapper userProfileMapper,
            RedisService redisService,
            QueueProducer queueProducer
    ) {
        super(userProfileMapper, redisService, queueProducer);
    }

    @Override
    public void onReject(Long userId, String reason) {
        int rows = userProfileMapper.update(
                new LambdaUpdateWrapper<UserProfile>()
                        .set(UserProfile::getAvatarUrl, null)
                        .eq(UserProfile::getId, userId)
        );

        if (rows <= 0) {
            return;
        }

        evictUserProfileCache(userId);
        sendRejectNotify(userId, "用户头像", "头像未通过审核，已为你清空", reason);
    }
}
