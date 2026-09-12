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
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target="title", source="title")
    @Mapping(target="summary", source="summary")
    @Mapping(target="category", source="category")
    @Mapping(target="eventType", source="eventType")
    @Mapping(target="editionLabel", source="editionLabel")
    @Mapping(target="organizer", source="organizer")
    @Mapping(target="location", source="location")
    @Mapping(target="audienceScope", source="audienceScope")
    @Mapping(target="audienceSummary", source="audienceSummary")
    @Mapping(target="contactName", source="contactName")
    @Mapping(target="contactPhone", source="contactPhone")
    @Mapping(target="registrationMode", source="registrationMode")
    @Mapping(target="participantMode", source="participantMode")
    @Mapping(target="capacity", source="capacity")
    @Mapping(target="capacityUnit", source="capacityUnit")
    @Mapping(target="timeDescription", source="timeDescription")
    @Mapping(target="content", source="content")
    @Mapping(target="startTime", source="startTime")
    @Mapping(target="startPrecision", source="startPrecision")
    @Mapping(target="endTime", source="endTime")
    @Mapping(target="endPrecision", source="endPrecision")
    @Mapping(target="registrationStart", source="registrationStart")
    @Mapping(target="registrationStartPrecision", source="registrationStartPrecision")
    @Mapping(target="registrationEnd", source="registrationEnd")
    @Mapping(target="registrationEndPrecision", source="registrationEndPrecision")
    ExamInfo toOperationsEntity(AdminPublicEventSaveBO command);

    cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO toEventSectionBO(cn.jualn.miniapp.module.exam.dto.request.EventSectionRequest request);
    cn.jualn.miniapp.module.exam.vo.EventSectionVO toEventSectionVO(cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO bo);
    java.util.List<cn.jualn.miniapp.module.exam.vo.EventSectionVO> toEventSectionVOs(java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO> bo);

    cn.jualn.miniapp.module.eventcontent.bo.EventActionBO toEventActionBO(cn.jualn.miniapp.module.exam.dto.request.EventActionRequest request);
    cn.jualn.miniapp.module.exam.vo.EventActionVO toEventActionVO(cn.jualn.miniapp.module.eventcontent.bo.EventActionBO bo);
    java.util.List<cn.jualn.miniapp.module.exam.vo.EventActionVO> toEventActionVOs(java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventActionBO> bo);


    List<ExamSimpleVO> toSimpleVOList(List<ExamSimpleBO> simpleBOs);

    ExamCreateBO toCreateBO(ExamCreateRequest request);

    @Mapping(source = "mediaList", target = "attachmentItems")
    @Mapping(source = "timelineList", target = "timelineItems")
    ExamUpdateBO toUpdateBO(ExamUpdateRequest request);

    ExamPageBO toPageBO(ExamPageQuery query);

    @Mapping(target = "attachmentItems", ignore = true)
    @Mapping(target = "timelineItems", ignore = true)
    List<ExamDetailBO> toDetailList(List<ExamInfo> list);

    @Mapping(target="organizer", ignore=true)
    @Mapping(target="location", ignore=true)
    @Mapping(target="audienceScope", ignore=true)
    @Mapping(target="audienceSummary", ignore=true)
    @Mapping(target="contactName", ignore=true)
    @Mapping(target="contactPhone", ignore=true)
    @Mapping(target="registrationMode", ignore=true)
    @Mapping(target="participantMode", ignore=true)
    @Mapping(target="capacity", ignore=true)
    @Mapping(target="capacityUnit", ignore=true)
    @Mapping(target="coverAttachmentId", ignore=true)
    @Mapping(target="cancelledAt", ignore=true)
    @Mapping(target="cancelReason", ignore=true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "publishStatus", ignore = true)
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
    @Mapping(target="organizer", ignore=true)
    @Mapping(target="location", ignore=true)
    @Mapping(target="audienceScope", ignore=true)
    @Mapping(target="audienceSummary", ignore=true)
    @Mapping(target="contactName", ignore=true)
    @Mapping(target="contactPhone", ignore=true)
    @Mapping(target="registrationMode", ignore=true)
    @Mapping(target="participantMode", ignore=true)
    @Mapping(target="capacity", ignore=true)
    @Mapping(target="capacityUnit", ignore=true)
    @Mapping(target="coverAttachmentId", ignore=true)
    @Mapping(target="cancelledAt", ignore=true)
    @Mapping(target="cancelReason", ignore=true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "publishStatus", ignore = true)
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

    @Mapping(target="liked", ignore=true)
    @Mapping(target="subscribed", ignore=true)
    @Mapping(target = "author", ignore = true)
    @Mapping(target = "attachmentItems", ignore = true)
    @Mapping(target = "timelineItems", ignore = true)
    @Mapping(target = "activityPhase", ignore = true)
    @Mapping(target = "registrationStatus", ignore = true)
    @Mapping(target = "sections", ignore = true)
    @Mapping(target = "actions", ignore = true)
    ExamDetailBO toDetailBO(ExamInfo examInfo);

    ExamDetailVO toDetailVO(ExamDetailBO examDetailBO);

    ExamVO toVO(ExamDetailBO examDetailBO);

    List<ExamVO> toVOList(List<ExamDetailBO> examDetailBOList);
}





