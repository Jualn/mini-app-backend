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

import java.util.UUID;

@Slf4j
@Component
@AuditTarget(AuditScene.USER_NICKNAME)
public class UserNicknameAuditCallback extends AbstractUserProfileAuditCallback {

    public UserNicknameAuditCallback(
            UserProfileMapper userProfileMapper,
            RedisService redisService,
            QueueProducer queueProducer
    ) {
        super(userProfileMapper, redisService, queueProducer);
    }

    @Override
    public void onReject(Long userId, String reason) {
        String defaultNickname = "用户" + UUID.randomUUID().toString().substring(0, 6);

        int rows = userProfileMapper.update(
                new LambdaUpdateWrapper<UserProfile>()
                        .set(UserProfile::getNickname, defaultNickname)
                        .eq(UserProfile::getId, userId)
        );

        if (rows <= 0) {
            log.warn("[UserNameAuditCallback] 用户不存在或昵称重置失败，userId={}", userId);
            return;
        }

        evictUserProfileCache(userId);
        sendRejectNotify(userId, "用户昵称", "昵称未通过审核，已为你重置", reason);
    }
}
