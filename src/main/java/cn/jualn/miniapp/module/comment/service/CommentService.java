package cn.jualn.miniapp.module.comment.service;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.comment.bo.CommentCreateBO;
import cn.jualn.miniapp.module.comment.bo.AdminCommentActionBO;
import cn.jualn.miniapp.module.comment.bo.CommentPageBO;
import cn.jualn.miniapp.module.comment.vo.CommentVO;
import cn.jualn.miniapp.module.comment.vo.ReplyVO;
import org.springframework.transaction.annotation.Transactional;

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

	void takeDownComment(AdminCommentActionBO command);

	void restoreComment(AdminCommentActionBO command);

	/** 人工复核通过并发布机器风险评论。 */
	void approveCommentReview(Long commentId, Long operatorId, String remark);

	/** 人工复核拒绝机器风险评论。 */
	void rejectCommentReview(Long commentId, Long operatorId, String reason);
}
