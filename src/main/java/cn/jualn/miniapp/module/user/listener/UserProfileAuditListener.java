package cn.jualn.miniapp.module.user.listener;

import cn.jualn.miniapp.module.user.audit.UserProfileAuditSubmitter;
import cn.jualn.miniapp.module.user.event.UserProfileUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 用户资料审核监听器。
 *
 * <p>用户资料更新事务提交后，再提交内容安全审核任务。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileAuditListener {

    private final UserProfileAuditSubmitter auditSubmitter;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserProfileUpdated(UserProfileUpdatedEvent event) {
        if (event == null || event.getUserId() == null || event.getUpdateBO() == null) {
            return;
        }

        try {
            auditSubmitter.submit(event.getUserId(), event.getUpdateBO());
        } catch (Exception e) {
            log.error("[UserProfileAuditListener] 用户资料审核提交失败，userId={}",
                    event.getUserId(), e);
        }
    }
}
