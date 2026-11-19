package cn.jualn.miniapp.module.auth.service.impl;

import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.module.user.service.UserAccountService;
import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.module.user.enums.UserRole;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.module.auth.vo.LoginVO;
import cn.jualn.miniapp.module.auth.service.AuthService;
import cn.jualn.miniapp.module.user.dto.inner.UserInfoDTO;
import cn.jualn.miniapp.third.wx.client.WxClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 认证服务实现（微信小程序登录）。
 * <p>
 * 职责：根据 wx.login 返回的 code 换取 openid，完成用户注册/登录，并签发 Sa-Token。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final WxClient wxClient;
    private final UserWechatIdentityService userIdentities;
    private final UserAccountService userAccounts;

    /**
     * 小程序登录。
     * <p>
     * 流程：code -> openid -> 查用户（不存在则自动创建）-> Sa-Token 登录 -> 返回 token。
     *
     * @param code 前端通过 wx.login 获取的一次性 code
     * @return 登录响应体
     * @throws BusinessException 当用户创建后仍无法获取 userId 时抛出服务异常
     */
    @Override
    public LoginVO login(String code) {
        long start = System.currentTimeMillis();
        log.debug("[AuthService.login][开始] codeLength={}", code.length());

        // WxClient 内部负责与微信服务交互以及失败场景处理，这里只关心 openid。
        String openid = wxClient.getMiniSession(code).getOpenid();
        log.debug("[AuthService.login] openid={}", maskOpenid(openid));

        UserInfoDTO userInfo = userIdentities.getUserInfo(openid); // 确保用户存在（不存在则创建）

        Long userId = userInfo.getId();

        if (userId == null) {
            // insert 后仍拿不到主键，属于不应发生的防御性兜底。
            throw new SystemException("登录后用户ID为空");
        }
        userAccounts.assertLoginAllowed(userId);

        long version = userAccounts.sessionVersion(userId);
        StpUtil.login(userId, new cn.dev33.satoken.stp.parameter.SaLoginParameter()
                .setExtra(UserAccountService.SESSION_VERSION, Long.toString(version)));
        userAccounts.assertLoginAllowed(userId);
        userAccounts.requireSessionVersion(userId, version);
        // 将当前用户角色写入会话，供后续鉴权扩展使用。
        StpUtil.getSession().set("role", UserRole.fromCode(userInfo.getRole()));

        log.info("result=success operation=login userId={} costMs={}", userId, System.currentTimeMillis() - start);

        return LoginVO.builder()
                .token(StpUtil.getTokenValue())
                .userInfo(userInfo)
                .build();
    }

    @Override
    public void logout() {
        // No current-account or business-grant check: even restricted sessions may end themselves.
        // Redis errors propagate; an unknown logout outcome must not be reported as success.
        try {
            if (StpUtil.isLogin()) {
                long user = StpUtil.getLoginIdAsLong();
                StpUtil.logout();
                log.info("result=success operation=logout userId={}", user);
            }
        } catch (cn.dev33.satoken.exception.NotLoginException ended) {
            // An expired or already-ended session is the same idempotent result.
        } catch (cn.dev33.satoken.jwt.exception.SaJwtException invalid) {
            throw new cn.jualn.miniapp.common.exception.BusinessException(cn.jualn.miniapp.common.result.ResultCode.UNAUTHORIZED);
        } catch (RuntimeException failure) {
            var unavailable = new cn.jualn.miniapp.common.exception.ContractProblemException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "/problems/authentication-unavailable", "Session termination cannot be confirmed");
            unavailable.initCause(failure);
            throw unavailable;
        }
    }

    private String maskOpenid(String openid) {
        if (openid == null || openid.length() <= 6) {
            return "***";
        }
        return openid.substring(0, 3) + "***" + openid.substring(openid.length() - 3);
    }
}
