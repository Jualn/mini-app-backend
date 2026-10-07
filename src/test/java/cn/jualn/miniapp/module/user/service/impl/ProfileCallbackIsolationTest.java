package cn.jualn.miniapp.module.user.service.impl;

import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.user.audit.*;
import cn.jualn.miniapp.module.user.mapper.UserProfileMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class ProfileCallbackIsolationTest {
    @Test void latePassRejectAndRepeatedCallbacksNeverOverwriteOrNotifyNewProfiles() {
        var mapper = mock(UserProfileMapper.class); var cache = mock(RedisService.class); var notify = mock(NotifyService.class);
        var callbacks = List.of(new UserAvatarAuditCallback(mapper, cache, notify), new UserBioAuditCallback(mapper, cache, notify),
                new UserNicknameAuditCallback(mapper, cache, notify), new UserBackgroundAuditCallback(mapper, cache, notify));
        for (var callback : callbacks) {
            callback.onPass(7L, 1L); callback.onReject(7L, 1L, "synthetic reject");
            callback.onPass(7L, 2L); callback.onReject(7L, 1L, "replayed old reject");
        }
        verifyNoInteractions(mapper, cache, notify);
    }
}
