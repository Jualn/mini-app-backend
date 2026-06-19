package cn.jualn.miniapp.module.exam.converter;

import cn.jualn.miniapp.module.exam.bo.*;
import cn.jualn.miniapp.module.exam.dto.request.ExamCreateRequest;
import cn.jualn.miniapp.module.exam.dto.request.ExamPageQuery;
import cn.jualn.miniapp.module.exam.dto.request.ExamUpdateRequest;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.exam.vo.ExamSimpleVO;
import cn.jualn.miniapp.module.timeline.converter.TimelineConverter;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.exam.vo.ExamDetailVO;
import cn.jualn.miniapp.module.exam.vo.ExamVO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * 考试对象转换器。
 */
@Mapper(componentModel = "spring", uses = {TimelineConverter.class, MediaConverter.class, cn.jualn.miniapp.common.mapper.EnumConverter.class})
public interface ExamConverter {

    List<ExamSimpleVO> toSimpleVOList(List<ExamSimpleBO> simpleBOs);

    ExamCreateBO toCreateBO(ExamCreateRequest request);

    @Mapping(source = "mediaList", target = "attachmentItems")
    @Mapping(source = "timelineList", target = "timelineItems")
    ExamUpdateBO toUpdateBO(ExamUpdateRequest request);

    ExamPageBO toPageBO(ExamPageQuery query);

    @Mapping(target = "attachmentItems", ignore = true)
    @Mapping(target = "timelineItems", ignore = true)
    List<ExamDetailBO> toDetailList(List<ExamInfo> list);

    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "auditStatus", ignore = true)
    @Mapping(target = "rejectReason", ignore = true)
    @Mapping(target = "isPinned", ignore = true)
    @Mapping(target = "commentCount", ignore = true)
    @Mapping(target = "likeCount", ignore = true)
    @Mapping(target = "viewCount", ignore = true)
    @Mapping(target = "publishedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntityFromUpdateBO(@MappingTarget ExamInfo entity, ExamUpdateBO updateBO);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "auditStatus", ignore = true)
    @Mapping(target = "rejectReason", ignore = true)
    @Mapping(target = "isPinned", ignore = true)
    @Mapping(target = "commentCount", ignore = true)
    @Mapping(target = "likeCount", ignore = true)
    @Mapping(target = "viewCount", ignore = true)
    @Mapping(target = "publishedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    ExamInfo toEntity(ExamCreateBO request);

    @Mapping(target = "author", ignore = true)
    @Mapping(target = "attachmentItems", ignore = true)
    @Mapping(target = "timelineItems", ignore = true)
    ExamDetailBO toDetailBO(ExamInfo examInfo);

    @Mapping(target = "liked", ignore = true)
    @Mapping(target = "subscribed", ignore = true)
    ExamDetailVO toDetailVO(ExamDetailBO examDetailBO);

    ExamVO toVO(ExamDetailBO examDetailBO);

    List<ExamVO> toVOList(List<ExamDetailBO> examDetailBOList);
}





