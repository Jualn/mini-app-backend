package cn.jualn.miniapp.module.admin.auth.service;

import cn.jualn.miniapp.module.admin.auth.vo.AdminIdentityVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrConfirmationVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrSessionCreateVO;
import cn.jualn.miniapp.module.admin.auth.vo.AdminQrSessionPollVO;

public interface AdminAuthService {

    AdminQrSessionCreateVO createQrSession(String clientIp);

    AdminQrSessionPollVO pollQrSession(String sessionId, String pollSecret);

    void cancelQrSession(String sessionId, String pollSecret);

    AdminQrConfirmationVO confirmQrSession(String sessionId, long userId);

    AdminIdentityVO getCurrentIdentity();

    void logoutCurrent();
}
