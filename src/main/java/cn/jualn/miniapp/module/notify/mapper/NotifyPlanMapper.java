package cn.jualn.miniapp.module.notify.mapper;

import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 通知计划 Mapper（按 NOTIFICATION_DESIGN.md 设计）。
 */
public interface NotifyPlanMapper extends BaseMapper<NotifyPlan> {

	/**
	 * 查询所有待发（status=0 且 send_at > now）的计划，用于启动恢复。
	 */
	@Select("SELECT * FROM notify_plan WHERE status = 0")
	List<NotifyPlan> selectPending();

	/**
	 * 根据来源（内容）查询计划 ID 列表，用于取消/作废。
	 *
	 * @param sourceType 来源类型（1=活动 2=考试）
	 * @param sourceId 内容 ID
	 * @return 计划 ID 列表
	 */
	@Select("SELECT id FROM notify_plan WHERE source_type = #{sourceType} AND source_id = #{sourceId}")
	List<Long> selectIdsBySource(Integer sourceType, Long sourceId);

	/**
	 * 按来源批量作废计划（status 改为 2）。
	 *
	 * @param sourceType 来源类型（1=活动 2=考试）
	 * @param sourceId 内容 ID
	 * @return 受影响的行数
	 */
	@Update("UPDATE notify_plan SET status = 2 WHERE source_type = #{sourceType} AND source_id = #{sourceId} AND status = 0")
	int cancelBySource(Integer sourceType, Long sourceId);
}
