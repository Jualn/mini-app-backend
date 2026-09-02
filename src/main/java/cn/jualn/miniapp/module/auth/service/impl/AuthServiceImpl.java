package cn.jualn.miniapp.module.auth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.SystemException;
import cn.jualn.miniapp.module.auth.dto.LoginVO;
import cn.jualn.miniapp.module.auth.service.AuthService;
import cn.jualn.miniapp.module.user.dto.inner.UserInfoDTO;
import cn.jualn.miniapp.module.user.service.UserService;
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
    private final UserService userService;

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
        log.info("[AuthService.login][开始] codeLength={}", code.length());

        // WxClient 内部负责与微信服务交互以及失败场景处理，这里只关心 openid。
        String openid = wxClient.getMiniSession(code).getOpenid();
        log.debug("[AuthService.login] openid={}", maskOpenid(openid));

        UserInfoDTO userInfo = userService.getUserInfo(openid); // 确保用户存在（不存在则创建）

        Long userId = userInfo.getId();

        if (userId == null) {
            // insert 后仍拿不到主键，属于不应发生的防御性兜底。
            log.error("[AuthService.login][无用户] userId is null after persistence, openid={}", maskOpenid(openid));
            throw new SystemException("登录后用户ID为空");
        }
        userService.assertLoginAllowed(userId);

        StpUtil.login(userId);
        // 将当前用户角色写入会话，供后续鉴权扩展使用。
        StpUtil.getSession().set("role", UserRole.fromCode(userInfo.getRole()));

        log.info("[AuthService.login][完成] userId={}, costMs={}", userId, System.currentTimeMillis() - start);

        return LoginVO.builder()
                .token(StpUtil.getTokenValue())
                .userInfo(userInfo)
                .build();
    }

    /**
     * 对 openid 做最小脱敏，避免敏感标识出现在明文日志中。
     */
    private String maskOpenid(String openid) {
        if (openid == null || openid.length() <= 6) {
            return "***";
        }
        return openid.substring(0, 3) + "***" + openid.substring(openid.length() - 3);
    }
}
