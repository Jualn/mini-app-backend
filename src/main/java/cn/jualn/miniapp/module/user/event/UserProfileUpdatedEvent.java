package cn.jualn.miniapp.module.user.event;

import cn.jualn.miniapp.module.user.bo.UserProfileUpdateBO;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户资料更新事件。
 *
 * <p>用于在用户资料事务提交后触发资料审核，不让 UserServiceImpl 直接依赖 AuditService。</p>
 */
@Getter
@AllArgsConstructor
public class UserProfileUpdatedEvent {

    private final Long userId;

    private final UserProfileUpdateBO updateBO;
}
