package cn.jualn.miniapp.module.user.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.module.user.bo.*;
import cn.jualn.miniapp.module.user.dto.inner.UserInfoDTO;
import cn.jualn.miniapp.module.user.entity.UserAgreement;

import java.util.Collection;
import java.util.Map;

/**
 * 用户基础信息服务。
 */
public interface UserService {

	/**
	 * 获取用户基本信息。
	 *
	 * @param openid 微信小程序openid
	 * @return 用户基本信息，用于拼接给登录返回
	 */
	UserInfoDTO getUserInfo(String openid);

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

	UserAuthBO getUserAuthInfo(Long userId);

	/** 校验用户是否允许登录。 */
	void assertLoginAllowed(Long userId);

	/** 校验用户是否允许创建公开内容。 */
	void assertContentCreationAllowed(Long userId);

	void muteUser(AdminUserRestrictionBO command);

	void banUser(AdminUserRestrictionBO command);

	void restoreUser(AdminUserRestrictionBO command);

	void changeUserRole(AdminUserRoleChangeBO command);

	/**
	 * 获取用户的微信小程序 openid。
	 *
	 * @param userId 用户 ID。
	 * @return 用户的 openid。
	 * @exception BusinessException 如果用户未绑定微信小程序账号或发生其他错误。
	 */
	String getMiniOpenid(Long userId);

	/**
	 * 分页列出所有用户 ID（用于全员广播）。
	 *
	 * @param lastId 游标
	 * @param limit 分页大小
	 * @return 用户 ID 列表
	 */
	java.util.List<Long> listAllUserIds(long lastId, int limit);

	/**
	 * 更新当前登录用户的基础信息。
	 *
	 * @param bo 待更新的用户资料
	 */
	UserProfileBO updateCurrentProfile(UserProfileUpdateBO bo);

    /**
	 * 当前登录用户同意指定版本的协议。
	 *
	 * @param version 协议版本
	 */
	void agreeCurrentAgreement(String version);

	/**
	 * 查询当前登录用户的协议同意记录。
	 *
	 * @return 用户协议记录，不存在则返回 null
	 */
	UserAgreement getCurrentAgreement();
}
