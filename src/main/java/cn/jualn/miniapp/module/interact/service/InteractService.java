package cn.jualn.miniapp.module.interact.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.interact.bo.UserLikeBO;
import cn.jualn.miniapp.module.interact.dto.inner.UserLikeQuery;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface InteractService {

	/**
	 * 点赞目标内容。
	 *
	 * @param targetType 目标类型：1-帖子 2-活动 3-考试信息 4-评论
	 * @param targetId   目标ID
	 */
	void like(TargetType targetType, Long targetId);

	/**
	 * 取消点赞。
	 *
	 * @param targetType 目标类型：1-帖子 2-活动 3-考试信息 4-评论
	 * @param targetId   目标ID
	 */
	void unlike(TargetType targetType, Long targetId);

	/**
	 * 查询当前用户是否已点赞。
	 *
	 * @param targetType 目标类型
	 * @param targetId   目标ID
	 * @return 是否已点赞
	 */
	boolean isLiked(TargetType targetType, Long targetId);

	Map<Long, Boolean> batchIsLiked(TargetType targetType, Collection<Long> targetIds);

	/**
	 * 查询点赞数。
	 *
	 * @param targetType 目标类型
	 * @param targetId   目标ID
	 * @return 点赞数
	 */
	long getLikeCount(TargetType targetType, Long targetId);

	Map<Long, Integer> batchGetLikeCount(TargetType targetType, Collection<Long> targetIds);

    List<UserLikeBO> pageUserLikes(UserLikeQuery query);

    /**
	 * 记录分享行为。
	 *
	 * @param targetType 目标类型：1-帖子 2-活动 3-考试信息
	 * @param targetId   目标ID
	 * @param platform   分享平台：1-微信好友 2-朋友圈
	 */
	void share(TargetType targetType, Long targetId, Integer platform);

	/**
	 * 查询分享数。
	 *
	 * @param targetType 目标类型
	 * @param targetId   目标ID
	 * @return 分享数
	 */
	long getShareCount(TargetType targetType, Long targetId);

	/**
	 * 记录浏览行为。
	 *
	 * @param targetType 目标类型：1-帖子 2-活动 3-考试信息
	 * @param targetId   目标ID
	 */
	void view(TargetType targetType, Long targetId);

	/**
	 * 查询浏览量。
	 *
	 * @param targetType 目标类型
	 * @param targetId   目标ID
	 * @return 浏览量
	 */
	long getViewCount(TargetType targetType, Long targetId);

    Map<Long, Integer> batchGetViewCount(TargetType targetType, Collection<Long> targetIds);
}
