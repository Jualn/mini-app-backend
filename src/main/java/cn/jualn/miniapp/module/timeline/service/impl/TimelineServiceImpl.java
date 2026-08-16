package cn.jualn.miniapp.module.timeline.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.timeline.bo.*;
import cn.jualn.miniapp.module.timeline.converter.TimelineConverter;
import cn.jualn.miniapp.module.timeline.entity.Timeline;
import cn.jualn.miniapp.module.timeline.mapper.TimelineMapper;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Set;

/**
 * 时间线服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineServiceImpl implements TimelineService {

    private static final Set<TargetType> SUPPORTED_TARGET_TYPES = Set.of(
            TargetType.ACTIVITY,
            TargetType.EXAM
    );

    private final TimelineMapper timelineMapper;
    private final TimelineConverter timelineConverter;
    private final TargetValidator targetValidator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceTimelines(TimelineSaveBO saveDTO) {
        if (saveDTO == null) {
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "saveDTO 不能为空");
        }
        TargetType targetType = saveDTO.getTargetType();
        Long targetId = saveDTO.getTargetId();
        List<TimelineItemBO> requestList = saveDTO.getTimelines();

        assertTargetType(targetType);
        if (targetId == null) {
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "targetId 不能为空");
        }
        if (CollectionUtils.isEmpty(requestList)) {
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间线列表不能为空");
        }
        targetValidator.assertExists(targetType, targetId);

        LambdaQueryWrapper<Timeline> wrapper = Wrappers.lambdaQuery(Timeline.class)
                .eq(Timeline::getTargetType, targetType.getCode())
                .eq(Timeline::getTargetId, targetId);
        timelineMapper.delete(wrapper);


        List<Timeline> timelines = timelineConverter.toTimelineList(saveDTO);
        if (CollectionUtils.isEmpty(timelines)) {
            log.error("转换时间线列表失败, timelines is empty, saveDTO: {}", saveDTO);
            throw new BusinessException(ResultCode.TIMELINE_OPERATION_FAILED, "转换时间线列表失败");
        }

        timelineMapper.insert(timelines);

        log.info("覆盖保存时间线节点成功, targetType: {}, targetId: {}, count: {}",
                targetType, targetId, timelines.size());
    }

    @Override
    public void updateTimeline(TimelineUpdateBO command) {

        // 更新字段
        int updated = timelineMapper.updateById(
                timelineConverter.toEntity(command));
        if (updated <= 0) {
            log.error("更新时间线节点失败, id: {}", command.getId());
            throw new BusinessException(ResultCode.TIMELINE_OPERATION_FAILED, "更新时间线节点失败");
        }

        log.info("更新时间线节点成功, id: {}", command.getId());
    }

    @Override
    public void deleteTimeline(Long id) {
        int deleted = timelineMapper.deleteById(id);
        if (deleted > 0) {
            log.info("删除时间线节点成功, id: {}", id);
        } else {
            log.warn("删除时间线节点失败, id: {}", id);
            throw new BusinessException(ResultCode.TIMELINE_OPERATION_FAILED, "删除时间线节点失败");
        }
    }

    @Override
    public List<TimelineItemDTO> listTimelinesByTarget(TargetType targetType, Long targetId) {
        assertTargetType(targetType);
        targetValidator.assertExists(targetType, targetId);

        LambdaQueryWrapper<Timeline> wrapper = Wrappers.lambdaQuery(Timeline.class)
                .eq(Timeline::getTargetType, targetType.getCode())
                .eq(Timeline::getTargetId, targetId)
                .orderByAsc(Timeline::getSortOrder)
                .orderByAsc(Timeline::getId);

        List<Timeline> timelines = timelineMapper.selectList(wrapper);

        return timelineConverter.toItemDTOList(timelines);
    }

    private void assertTargetType(TargetType targetType) {
        if (!SUPPORTED_TARGET_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.TIMELINE_TARGET_UNSUPPORTED, "不支持的目标类型: " + targetType);
        }
    }
}

