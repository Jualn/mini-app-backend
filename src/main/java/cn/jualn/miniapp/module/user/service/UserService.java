package cn.jualn.miniapp.module.user.service;

import cn.jualn.miniapp.common.web.NameReadBO;

import cn.jualn.miniapp.module.user.bo.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 用户基础信息服务。
 */
public interface UserService {
    List<NameReadBO> adminUserNames(List<String> ids);

    AdminUserSummaryBO adminSummary();

    EffectiveProfileBO getEffectiveProfile(Long userId);

    EffectiveProfileBO updateEffectiveProfile(UserProfileUpdateBO command);

	/**
	 * 获取当前登录用户的基础信息。
	 *
	 * @return 当前用户资料
	 */
	UserProfileBO getCurrentProfile();

	/**
	 * 按用户 ID 实时读取资料，不使用认证信息缓存。
	 *
	 * @param userId 用户 ID
	 * @return 用户资料
	 */
	UserProfileBO getUserProfile(Long userId);

	AdminUserPageBO pageAdminUsers(AdminUserQueryBO query);

	AdminUserDetailBO getAdminUserDetail(Long userId);

	/**
	 * 获取公开展示的用户资料。
	 *
	 * @param userId 目标用户 ID
	 * @return 公开用户资料
	 */
	UserPublicProfileBO getPublicProfile(Long userId);

	/**
	 * 获取用户简版资料（跨服务 DTO）。
	 *
	 * @param userId 目标用户 ID
	 * @return 简版用户资料
	 */
	UserSimpleBO getSimpleInfo(Long userId);

	Map<Long, UserSimpleBO> batchGetSimple(Collection<Long> userIds);

	/**
	 * 分页列出所有用户 ID（用于全员广播）。
	 *
	 * @param lastId 游标
	 * @param limit 分页大小
	 * @return 用户 ID 列表
	 */
	List<Long> listAllUserIds(long lastId, int limit);

	/**
	 * 更新当前登录用户的基础信息。
	 *
	 * @param bo 待更新的用户资料
	 */
	UserProfileBO updateCurrentProfile(UserProfileUpdateBO bo);
}
