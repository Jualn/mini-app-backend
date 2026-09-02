package cn.jualn.miniapp.common.security;

import cn.dev33.satoken.jwt.StpLogicJwtForSimple;
import cn.dev33.satoken.stp.StpLogic;

/**
 * 管理端专用 Sa-Token 逻辑。
 *
 * <p>管理端与小程序使用不同 loginType，避免小程序 Token 被直接用于管理端接口。</p>
 */
public final class AdminStpUtil {

    public static final String LOGIN_TYPE = "admin";
    public static final StpLogic STP_LOGIC = new StpLogicJwtForSimple(LOGIN_TYPE);

    private AdminStpUtil() {
    }
}
