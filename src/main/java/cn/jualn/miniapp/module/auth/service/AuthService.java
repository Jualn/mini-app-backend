package cn.jualn.miniapp.module.auth.service;

import cn.jualn.miniapp.module.auth.dto.LoginVO;

/**
 * 认证服务接口。
 */
public interface AuthService {

	/**
	 * 小程序登录并返回 token。
	 *
	 * @param code 前端通过 wx.login 获取的一次性 code
	 * @return 登录响应体
	 */
	LoginVO login(String code);
}
