package cn.jualn.miniapp.module.exam.mapper;

import cn.jualn.miniapp.module.exam.bo.ExamSimpleBO;
import cn.jualn.miniapp.module.exam.bo.HomePublicMatterReminderRow;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.interact.dto.inner.InteractCountDTO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 考试信息数据访问层。
 *
 * <p>集中承载考试相关SQL，避免在Service中拼装SQL语句。</p>
 *
 * @author miniapp
 * @since 2026-04-28
 */
@Mapper
public interface ExamInfoMapper extends BaseMapper<ExamInfo> {
    List<HomePublicMatterReminderRow> selectHomePublicMatterReminders(
            @Param("userId") Long userId,
            @Param("evaluatedAt") LocalDateTime evaluatedAt,
            @Param("limit") int limit);

    List<cn.jualn.miniapp.module.exam.bo.AdminPublicEventListBO> selectOperationsPage(
            @Param("query") cn.jualn.miniapp.module.exam.bo.AdminPublicEventQueryBO query,
            @Param("lastId") Long lastId, @Param("limit") int limit);
    int saveOperationsFields(@Param("event") ExamInfo event);
    int publishDirectly(@Param("id") Long id);
    int takeDownDirectly(@Param("id") Long id);
    int cancelDirectly(@Param("id") Long id, @Param("reason") String reason);
    int removeDirectly(@Param("id") Long id);

    ExamInfo selectForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);

	/**
	 * 查询考试简要列表。
	 *
	 * @return 仅包含id、title、examDate字段的列表
	 */
	List<ExamSimpleBO> selectSimpleExams();

	/**
	 * 根据ID查询考试信息（排除已删除）。
	 *
	 * @param examId 考试ID
	 * @return 考试信息，不存在或已删除返回null
	 */
	ExamInfo selectByIdNotDeleted(@Param("examId") Long examId);

	/**
	 * 统计考试信息数量（排除已删除）。
	 *
	 * @param examId 考试ID
	 * @return 记录数量
	 */
	Long countByIdNotDeleted(@Param("examId") Long examId);

	/**
	 * 分页查询考试列表。
	 *
	 * <p>支持状态、分类、关键词、游标分页，动态SQL在XML中维护。</p>
	 *
	 * @param status 状态
	 * @param category 分类，可为空
	 * @param keyword 关键词，可为空
	 * @param lastId 游标ID，可为空
	 * @param limit 查询条数（通常为pageSize + 1）
	 * @return 考试列表
	 */
	List<ExamInfo> selectPageExams(@Param("status") Integer status,
								   @Param("category") Integer category,
								   @Param("keyword") String keyword,
								   @Param("lastId") Long lastId,
								   @Param("limit") Integer limit);

	/**
	 * 增加考试点赞数。
	 *
	 * @param examId 考试ID
	 */
	@Update("UPDATE exam_info SET like_count = IFNULL(like_count, 0) + 1 WHERE id = #{examId}")
	void increaseLikeCount(@Param("examId") Long examId);

	/**
	 * 减少考试点赞数（最低为0）。
	 *
	 * @param examId 考试ID
	 */
	@Update("UPDATE exam_info SET like_count = IF(IFNULL(like_count, 0) > 0, like_count - 1, 0) WHERE id = #{examId}")
	void decreaseLikeCount(@Param("examId") Long examId);

	/**
	 * 增加考试评论数。
	 *
	 * @param examId 考试ID
	 */
	@Update("UPDATE exam_info SET comment_count = IFNULL(comment_count, 0) + 1 WHERE id = #{examId}")
	void increaseCommentCount(@Param("examId") Long examId);

	/**
	 * 减少考试评论数（最低为0）。
	 *
	 * @param examId 考试ID
	 */
	@Update("UPDATE exam_info SET comment_count = IF(IFNULL(comment_count, 0) > 0, comment_count - 1, 0) WHERE id = #{examId}")
	void decreaseCommentCount(@Param("examId") Long examId);

	@Update("UPDATE exam_info SET like_count = #{count} WHERE id = #{examId} AND deleted_at IS NULL")
	void setLikeCount(@Param("examId") Long examId, @Param("count") Long count);

	Long selectViewCountById(@Param("id") Long id);

	List<InteractCountDTO> selectViewCountBatch(@Param("ids") Collection<Long> ids);

	int incrementViewCount(@Param("id") Long id, @Param("delta") Long delta);
}
