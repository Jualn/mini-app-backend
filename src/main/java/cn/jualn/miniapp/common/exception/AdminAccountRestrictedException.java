package cn.jualn.miniapp.common.exception;

import cn.jualn.miniapp.common.result.ResultCode;

public final class AdminAccountRestrictedException extends BusinessException {
    public AdminAccountRestrictedException() {
        super(ResultCode.UNAUTHORIZED, "管理端登录状态已经失效");
    }
}
