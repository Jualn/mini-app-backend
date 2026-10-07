package cn.jualn.miniapp.module.timeline.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.timeline.bo.*;
import cn.jualn.miniapp.module.timeline.converter.TimelineConverter;
import cn.jualn.miniapp.module.timeline.entity.Timeline;
import cn.jualn.miniapp.module.timeline.mapper.TimelineMapper;
import cn.jualn.miniapp.module.timeline.model.TimelineSemantic;
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
import java.util.Map;
import java.util.LinkedHashMap;

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
        List<TimelineItemBO> requestList = saveDTO.getTimelines() == null
                ? List.of()
                : saveDTO.getTimelines();

        assertTargetType(targetType);
        if (targetId == null) {
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "targetId 不能为空");
        }
        targetValidator.assertExists(targetType, targetId);

        LambdaQueryWrapper<Timeline> wrapper = Wrappers.lambdaQuery(Timeline.class)
                .eq(Timeline::getTargetType, targetType.getCode())
                .eq(Timeline::getTargetId, targetId);
        List<Timeline> existing = timelineMapper.selectList(wrapper);
        if (requestList.size() > 100) throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间线最多100项");
        List<Timeline> timelines = timelineConverter.toTimelineList(saveDTO);
        java.util.Set<Long> retained = new java.util.HashSet<>();
        for (Timeline node : timelines) {
            Timeline previous = null;
            boolean canonical = node.getNodeKey() != null;
            if (node.getId() != null) {
                previous = existing.stream().filter(t -> node.getId().equals(t.getId())).findFirst()
                        .orElseThrow(() -> new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间线ID不属于当前事项"));
            } else if (canonical) {
                previous = existing.stream().filter(t -> node.getNodeKey().equals(t.getNodeKey())).findFirst().orElse(null);
            } else {
                List<Timeline> matches = existing.stream().filter(t -> java.util.Objects.equals(t.getLabel(), node.getLabel()))
                        .filter(t -> !retained.contains(t.getId())).toList();
                if (matches.size() == 1) previous = matches.get(0);
            }
            if (previous != null) {
                node.setId(previous.getId());
                if (!retained.add(previous.getId())) throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间线ID重复");
                if (!canonical) {
                    if (node.getNodeType() == null) node.setNodeType(previous.getNodeType());
                    if (node.getNodeKey() == null) node.setNodeKey(previous.getNodeKey());
                    if (node.getLocation() == null) node.setLocation(previous.getLocation());
                    if (node.getTimeDescription() == null) node.setTimeDescription(previous.getTimeDescription());
                    if (node.getStartPrecision() == null && java.util.Objects.equals(node.getStartTime(), previous.getStartTime()))
                        node.setStartPrecision(previous.getStartPrecision());
                    if (node.getEndPrecision() == null && java.util.Objects.equals(node.getEndTime(), previous.getEndTime()))
                        node.setEndPrecision(previous.getEndPrecision());
                }
            }
            validateNode(node, canonical);
            if (previous == null) {
                if (node.getNodeKey() == null || node.getNodeKey().isBlank()) node.setNodeKey("legacy-" + java.util.UUID.randomUUID());
                if (timelineMapper.insert(node) != 1) throw new BusinessException(ResultCode.TIMELINE_OPERATION_FAILED);
            } else {
                int updated = timelineMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Timeline>()
                        .set(Timeline::getLabel, node.getLabel()).set(Timeline::getDescription, node.getDescription())
                        .set(Timeline::getStartTime, node.getStartTime()).set(Timeline::getEndTime, node.getEndTime())
                        .set(Timeline::getNodeType, node.getNodeType()).set(Timeline::getLocation, node.getLocation())
                        .set(Timeline::getNodeKey, node.getNodeKey())
                        .set(Timeline::getStartPrecision, node.getStartPrecision()).set(Timeline::getEndPrecision, node.getEndPrecision())
                        .set(Timeline::getTimeDescription, node.getTimeDescription()).set(Timeline::getSortOrder, node.getSortOrder())
                        .eq(Timeline::getId, node.getId()).eq(Timeline::getTargetType, targetType.getCode()).eq(Timeline::getTargetId, targetId));
                if (updated != 1) throw new BusinessException(ResultCode.TIMELINE_OPERATION_FAILED);
            }
        }
        List<Long> removed = existing.stream().map(Timeline::getId).filter(id -> !retained.contains(id)).toList();
        if (!removed.isEmpty()) timelineMapper.delete(new LambdaQueryWrapper<Timeline>()
                .eq(Timeline::getTargetType, targetType.getCode()).eq(Timeline::getTargetId, targetId).in(Timeline::getId, removed));
    }

    private void validateNode(Timeline node, boolean canonical) {
        if (node.getLabel() == null || node.getLabel().isBlank() || node.getLabel().length() > 120)
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间线名称不能为空或超过120字");
        if (node.getNodeType() == null) node.setNodeType(canonical ? TimelineSemantic.OTHER.name() : "CUSTOM");
        if (canonical) {
            TimelineSemantic semantic;
            try {
                semantic = TimelineSemantic.requireKnown(node.getNodeType());
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间线业务语义不合法");
            }
            if (!semantic.supports(TargetType.fromCode(node.getTargetType()))) {
                throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间线业务语义与主体类型不匹配");
            }
            node.setNodeType(semantic.name());
        } else if (!node.getNodeType().matches("[A-Z][A-Z0-9_]{0,31}")) {
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间线类型不合法");
        }
        if (node.getLocation() != null && node.getLocation().length() > 300)
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间线地点过长");
        if (node.getDescription() != null && node.getDescription().length() > 2000)
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间线说明过长");
        if (node.getTimeDescription() != null && node.getTimeDescription().length() > 500)
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间说明过长");
        if (node.getSortOrder() == null || node.getSortOrder() < 0 || node.getSortOrder() > 65535)
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "排序值不合法");
        node.setStartPrecision(precision(node.getStartTime(), node.getStartPrecision()));
        node.setEndPrecision(precision(node.getEndTime(), node.getEndPrecision()));
        cn.jualn.miniapp.module.eventcontent.service.EventTimePolicy.range(node.getStartTime(), node.getEndTime(), node.getEndPrecision());
    }

    private Integer precision(java.time.LocalDateTime time, Integer precision) {
        int value = precision == null ? (time == null ? 0 : 2) : precision;
        if (value < 0 || value > 2 || (time == null && value != 0))
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "时间与精度不匹配");
        if (time != null && value == 1 && !time.toLocalTime().equals(java.time.LocalTime.MIDNIGHT))
            throw new BusinessException(ResultCode.TIMELINE_PARAM_INVALID, "日期精度请提交当天零点");
        return value;
    }

    @Override
    public void updateTimeline(TimelineUpdateBO command) {

        // Standalone writes cannot verify owning event permissions or refresh aggregate audit/cache.
        throw new BusinessException(ResultCode.TIMELINE_OPERATION_FAILED, "请通过活动或公共事项编辑接口修改时间线");

    }

    @Override
    public void deleteTimeline(Long id) {
        throw new BusinessException(ResultCode.TIMELINE_OPERATION_FAILED, "请通过活动或公共事项编辑接口删除时间线");
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

    @Override
    public Map<Long, List<TimelineItemDTO>> listTimelinesByTargets(TargetType targetType, List<Long> targetIds) {
        assertTargetType(targetType);
        if (targetIds == null || targetIds.isEmpty()) return Map.of();
        List<Timeline> timelines = timelineMapper.selectList(Wrappers.lambdaQuery(Timeline.class)
                .eq(Timeline::getTargetType, targetType.getCode()).in(Timeline::getTargetId, targetIds)
                .orderByAsc(Timeline::getTargetId).orderByAsc(Timeline::getSortOrder).orderByAsc(Timeline::getId));
        Map<Long, List<TimelineItemDTO>> result = new LinkedHashMap<>();
        for (Timeline timeline : timelines) {
            result.computeIfAbsent(timeline.getTargetId(), ignored -> new java.util.ArrayList<>())
                    .add(timelineConverter.toItemDTO(timeline));
        }
        return result;
    }

    private void assertTargetType(TargetType targetType) {
        if (!SUPPORTED_TARGET_TYPES.contains(targetType)) {
            throw new BusinessException(ResultCode.TIMELINE_TARGET_UNSUPPORTED, "不支持的目标类型: " + targetType);
        }
    }
}

