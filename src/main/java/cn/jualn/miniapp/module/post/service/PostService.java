package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.post.bo.PostCreateBO;
import cn.jualn.miniapp.module.post.bo.AdminPostActionBO;
import cn.jualn.miniapp.module.post.bo.PostListBO;
import cn.jualn.miniapp.module.post.dto.request.PostPageQuery;
import cn.jualn.miniapp.module.post.vo.PostDetailVO;

/**
 * 帖子服务。
 */
public interface PostService {

	/**
	 * 创建帖子。
	 *
	 * @param request 创建请求
	 * @return 新帖子列表 BO
	 */
	PostListBO createPost(PostCreateBO request);

	/**
	 * 帖子广场分页列表（游标分页）。
	 *
	 * @param query 分页查询参数
	 * @return 分页结果
	 */
	PageResult<PostListBO> pagePost(PostPageQuery query);

	/**
	 * 用户点赞的帖子分页列表（游标分页）。
	 *
	 * @param userId 用户 ID
	 * @param lastLikeId 上一页最后一个点赞记录 ID，首次查询可为空
	 * @param pageSize 每页条数
	 * @return 分页结果
	 */
	PageResult<PostListBO> pageUserLikedPosts(Long userId, Long lastLikeId, Integer pageSize);

	/**
	 * 帖子搜索（游标分页）。
	 *
	 * @param keyword 搜索关键词
	 * @param lastId 上一页最后一个帖子 ID，首次查询可为空
	 * @param pageSize 每页条数
	 * @return 分页结果
	 */
	PageResult<PostListBO> searchPosts(String keyword, Long lastId, Integer pageSize);

    /**
	 * 获取帖子详情。
	 *
	 * @param postId 帖子 ID
	 * @return 帖子详情
	 */
	PostDetailVO getPostDetail(Long postId);

	/**
	 * 删除帖子（软删除）。
	 *
	 * @param postId 帖子 ID
	 */
	void removePost(Long postId);

	void pinPost(AdminPostActionBO command);

	void unpinPost(AdminPostActionBO command);

	void featurePost(AdminPostActionBO command);

	void unfeaturePost(AdminPostActionBO command);

	void takeDownPost(AdminPostActionBO command);

	/** 人工复核通过并发布机器风险帖子。 */
	void approvePostReview(Long postId, Long operatorId, String remark);

	/** 人工复核拒绝机器风险帖子。 */
	void rejectPostReview(Long postId, Long operatorId, String reason);

	/**
	 * 增加帖子评论数。
	 *
	 * @param postId 帖子ID
	 */
	void increaseCommentCount(Long postId);

	/**
	 * 减少帖子评论数（最低为0）。
	 *
	 * @param postId 帖子ID
	 */
	void decreaseCommentCount(Long postId);
}
