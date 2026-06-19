package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;
import cn.jualn.miniapp.module.comment.bo.CommentPageBO;
import cn.jualn.miniapp.module.comment.vo.CommentVO;
import cn.jualn.miniapp.module.comment.vo.ReplyVO;

/**
 * Comment service.
 */
public interface CommentService {

	/**
	 * Create a comment.
	 *
	 * @param command create command
	 * @return new comment id
	 */
	Long createComment(CommentCreateBO command);

	/**
	 * Page comments by target and parent id.
	 *
	 * @param query page query
	 * @return page result
	 */
	PageResult<CommentVO> pageComment(CommentPageBO query);

    PageResult<ReplyVO> pageReply(CommentPageBO query);

    /**
	 * Remove comment (soft delete).
	 *
	 * @param commentId comment id
	 */
	void removeComment(Long commentId);

}
