package cn.jualn.miniapp.module.timeline.converter;

import cn.jualn.miniapp.module.timeline.bo.*;
import cn.jualn.miniapp.module.timeline.dto.TimelineCreateRequest;
import cn.jualn.miniapp.module.timeline.dto.TimelineUpdateRequest;
import cn.jualn.miniapp.module.timeline.dto.request.TimelineItemRequest;
import cn.jualn.miniapp.module.timeline.entity.Timeline;
import cn.jualn.miniapp.module.timeline.vo.TimelineVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.stream.Collectors;

import cn.jualn.miniapp.common.mapper.EnumConverter;

@Mapper(componentModel = "spring", uses = EnumConverter.class)
public interface TimelineConverter {


    TimelineItemDTO toItemDTO(Timeline entity);

    List<TimelineItemDTO> toItemDTOList(List<Timeline> entity);

    @Mapping(target = "label", ignore = true)
    @Mapping(target = "description", ignore = true)
    @Mapping(target = "startTime", ignore = true)
    @Mapping(target = "endTime", ignore = true)
    @Mapping(target = "sortOrder", ignore = true)
    TimelineCreateBO toCreateBO(TimelineCreateRequest request);

    @Mapping(target = "targetType", ignore = true)
    @Mapping(target = "targetId", ignore = true)
    TimelineCreateBO toCreateBO(TimelineItemRequest request);

    TimelineUpdateBO toUpdateBO(TimelineUpdateRequest request);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Timeline toEntity(TimelineCreateBO bo);

    @Mapping(target = "targetType", ignore = true)
    @Mapping(target = "targetId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Timeline toEntity(TimelineUpdateBO bo);

    /**
     * BO 转 VO
     */
    TimelineVO toVO(TimelineItemDTO bo);

    List<TimelineVO> toVOList(List<TimelineItemDTO> bos);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    Timeline toTimeline(TimelineItemBO item, Integer targetType, Long targetId);

    // Integer<->Enum mappings delegated to EnumConverter

    default List<Timeline> toTimelineList(TimelineSaveBO saveBO){
        if (saveBO == null || saveBO.getTimelines() == null) {
            return null;
        }
        List<Timeline> entities = saveBO.getTimelines().stream()
                .map(item -> toTimeline(item, saveBO.getTargetType().getCode(), saveBO.getTargetId()))
                .collect(Collectors.toList());

        // 如果order为空，按照入参顺序设置
        for (int i = 0; i < entities.size(); i++) {
            Timeline timeline = entities.get(i);
            if (timeline.getSortOrder() == null) {
                timeline.setSortOrder(i + 1);  // 设置order，从1开始
            }
        }

        return entities;
    }
}
