package cn.jualn.miniapp.module.exam.service;

import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.module.exam.bo.ExamCreateBO;
import cn.jualn.miniapp.module.exam.bo.ExamDetailBO;
import cn.jualn.miniapp.module.exam.bo.ExamPageBO;
import cn.jualn.miniapp.module.exam.bo.ExamSimpleBO;
import cn.jualn.miniapp.module.exam.bo.ExamUpdateBO;

import java.util.List;

/**
 * 考试信息业务接口。
 */
public interface ExamService {

	/**
	 * 创建考试信息。
	 *
	 * @param command 创建业务对象
	 * @return 考试信息ID
	 */
	Long createExam(ExamCreateBO command);

	/**
	 * 更新考试信息。
	 *
	 * @param command 更新业务对象
	 */
	void updateExam(ExamUpdateBO command);

	/**
	 * 删除考试信息。
	 *
	 * @param id 考试ID
	 */
	void removeExam(Long id);

	/**
	 * 获取考试简要列表。
	 *
	 * @return 仅包含id、title、examDate字段的列表
	 */
	List<ExamSimpleBO> getExamSimple();

	/**
	 * 获取考试详情。
	 *
	 * @param id 考试ID
	 * @return 考试详情业务对象
	 */
	ExamDetailBO getExamDetail(Long id);

	/**
	 * 考试分页列表。
	 *
	 * @param command 查询业务对象
	 * @return 分页结果
	 */
	PageResult<ExamDetailBO> pageExam(ExamPageBO command);

	/**
	 * 增加考试评论数。
	 *
	 * @param examId 考试信息ID
	 */
	void increaseCommentCount(Long examId);

	/**
	 * 减少考试评论数（最低为0）。
	 *
	 * @param examId 考试信息ID
	 */
	void decreaseCommentCount(Long examId);
}
