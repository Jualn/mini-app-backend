package cn.jualn.miniapp.module.timeline.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.timeline.bo.TimelineUpdateBO;
import cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO;

import java.util.List;
import java.util.Map;

/**
 * 时间线服务接口
 */
public interface TimelineService {

    /**
     * 覆盖保存目标时间线列表
     */
    void replaceTimelines(TimelineSaveBO saveDTO);

    /**
     * 更新时间线节点
     */
    void updateTimeline(TimelineUpdateBO request);

    /**
     * 删除时间线节点
     */
    void deleteTimeline(Long id);

    /**
     * 获取活动/考试的所有时间线节点（按排序号排序）
     *
     * @param targetType 1-活动 2-考试信息
     * @param targetId   活动/考试ID
     * @return 时间线列表
     */
    List<TimelineItemDTO> listTimelinesByTarget(TargetType targetType, Long targetId);

    Map<Long, List<TimelineItemDTO>> listTimelinesByTargets(TargetType targetType, List<Long> targetIds);
}
