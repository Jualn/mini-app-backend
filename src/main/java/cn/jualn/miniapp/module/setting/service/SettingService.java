package cn.jualn.miniapp.module.setting.service;

import cn.jualn.miniapp.module.setting.bo.UserSettingBO;

/**
 * 用户设置服务。
 */
public interface SettingService {

	/**
	 * 获取当前登录用户的设置。
	 * 用户未配置过设置时，返回数据库默认值对应的结果。
	 *
	 * @return 当前用户设置
	 */
	UserSettingBO getCurrentSetting();

	UserSettingBO getSettingByUserId(Long userId);

	/**
	 * 更新当前登录用户的设置。
	 * 采用整表更新方式，前端一次提交全部开关项。
	 *
	 * @param setting 待更新的设置实体
	 */
	void updateCurrentSetting(UserSettingBO setting);
}
