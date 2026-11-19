package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.admin.auth.support.AdminPermissionPolicy;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PostDictionaryAuthorization {
    private final CurrentAuthorization authorization;

    public void read() {
        authorization.require(AdminStpUtil.STP_LOGIC.getLoginIdAsLong(), "post-dictionary:read", "POST", "post-dictionary:read");
    }

    public void write(long actor) {
        authorization.require(actor, "post-dictionary:manage", "POST", "post-dictionary:manage");
        if (AdminStpUtil.STP_LOGIC.getLoginIdAsLong() != actor) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }
}
