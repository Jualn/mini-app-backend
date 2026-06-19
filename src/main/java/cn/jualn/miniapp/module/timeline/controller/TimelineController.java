package cn.jualn.miniapp.module.timeline.controller;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.result.Result;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemDTO;
import cn.jualn.miniapp.module.timeline.converter.TimelineConverter;
import cn.jualn.miniapp.module.timeline.dto.TimelineUpdateRequest;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.timeline.vo.TimelineVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 时间线控制器
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/timeline")
public class TimelineController {

    private final TimelineService timelineService;
    private final TimelineConverter timelineConverter;

    /**
     * 更新时间线节点
     */
    @PutMapping("/{id}")
    public Result<Void> updateTimeline(
            @PathVariable Long id,
            @Valid @RequestBody TimelineUpdateRequest request) {
        if (id != null) {
            request.setId(id);
        }
        timelineService.updateTimeline(timelineConverter.toUpdateBO(request));
        return Result.ok(null);
    }

    /**
     * 删除时间线节点
     */
    @DeleteMapping("/{id}")
    public Result<Void> deleteTimeline(@PathVariable Long id) {
        timelineService.deleteTimeline(id);
        return Result.ok(null);
    }

    /**
     * 获取活动/考试的所有时间线节点
     *
     * @param targetType 1-活动 2-考试信息
     * @param targetId   活动/考试ID
     */
    @GetMapping("/target/{targetType}/{targetId}")
    public Result<List<TimelineVO>> listTimelinesByTarget(
            @PathVariable TargetType targetType,
            @PathVariable Long targetId) {
        List<TimelineItemDTO> result = timelineService.listTimelinesByTarget(targetType, targetId);

        return Result.ok(timelineConverter.toVOList(result));
    }
}
