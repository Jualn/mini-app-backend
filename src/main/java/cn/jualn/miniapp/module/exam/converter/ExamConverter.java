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
    @Mapping(target="eventType", source="eventType")
    @Mapping(target="sourceName", source="sourceName")
    @Mapping(target="sourceUrl", source="sourceUrl")
    @Mapping(target="officialUrl", source="officialUrl")
    @Mapping(target="coverAttachmentId", source="coverAttachmentId")
    @Mapping(target="contactsJson", source="contactsJson")
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
    @Mapping(target = "cardTimeline", ignore = true)
    List<ExamDetailBO> toDetailList(List<ExamInfo> list);

    @Mapping(target="coverAttachmentId", ignore=true)
    @Mapping(target="cancelledAt", ignore=true)
    @Mapping(target="cancelReason", ignore=true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "publishStatus", ignore = true)
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
    @Mapping(target="coverAttachmentId", ignore=true)
    @Mapping(target="cancelledAt", ignore=true)
    @Mapping(target="cancelReason", ignore=true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "publishStatus", ignore = true)
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
    @Mapping(target = "sections", ignore = true)
    @Mapping(target = "actions", ignore = true)
    @Mapping(target = "contacts", ignore = true)
    @Mapping(target = "cardTimeline", ignore = true)
    ExamDetailBO toDetailBO(ExamInfo examInfo);

    ExamDetailVO toDetailVO(ExamDetailBO examDetailBO);

    ExamVO toVO(ExamDetailBO examDetailBO);

    List<ExamVO> toVOList(List<ExamDetailBO> examDetailBOList);
}





