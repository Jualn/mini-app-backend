package cn.jualn.miniapp.module.publicevent.converter;

import cn.jualn.miniapp.module.publicevent.bo.*;
import cn.jualn.miniapp.module.publicevent.dto.request.PublicEventCreateRequest;
import cn.jualn.miniapp.module.publicevent.dto.request.PublicEventPageQuery;
import cn.jualn.miniapp.module.publicevent.dto.request.PublicEventUpdateRequest;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.vo.PublicEventSimpleVO;
import cn.jualn.miniapp.module.timeline.converter.TimelineConverter;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.module.publicevent.vo.PublicEventLegacyDetailVO;
import cn.jualn.miniapp.module.publicevent.vo.PublicEventLegacyVO;
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
public interface PublicEventConverter {
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target="title", source="title")
    @Mapping(target="summary", source="summary")
    @Mapping(target="eventType", source="eventType")
    @Mapping(target="sourceName", source="sourceName")
    @Mapping(target="sourceUrl", source="sourceUrl")
    @Mapping(target="officialUrl", source="officialUrl")
    @Mapping(target="coverAttachmentId", source="coverAttachmentId")
    @Mapping(target="contactsJson", source="contactsJson")
    PublicEvent toOperationsEntity(AdminPublicEventSaveBO command);

    cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO toEventSectionBO(cn.jualn.miniapp.module.publicevent.dto.request.EventSectionRequest request);
    cn.jualn.miniapp.module.publicevent.vo.EventSectionVO toEventSectionVO(cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO bo);
    java.util.List<cn.jualn.miniapp.module.publicevent.vo.EventSectionVO> toEventSectionVOs(java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO> bo);

    cn.jualn.miniapp.module.eventcontent.bo.EventActionBO toEventActionBO(cn.jualn.miniapp.module.publicevent.dto.request.EventActionRequest request);
    cn.jualn.miniapp.module.publicevent.vo.EventActionVO toEventActionVO(cn.jualn.miniapp.module.eventcontent.bo.EventActionBO bo);
    java.util.List<cn.jualn.miniapp.module.publicevent.vo.EventActionVO> toEventActionVOs(java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventActionBO> bo);


    List<PublicEventSimpleVO> toSimpleVOList(List<PublicEventSimpleBO> simpleBOs);

    PublicEventCreateBO toCreateBO(PublicEventCreateRequest request);

    @Mapping(source = "mediaList", target = "attachmentItems")
    @Mapping(source = "timelineList", target = "timelineItems")
    PublicEventUpdateBO toUpdateBO(PublicEventUpdateRequest request);

    PublicEventPageBO toPageBO(PublicEventPageQuery query);

    @Mapping(target = "attachmentItems", ignore = true)
    @Mapping(target = "timelineItems", ignore = true)
    @Mapping(target = "cardTimeline", ignore = true)
    List<PublicEventDetailBO> toDetailList(List<PublicEvent> list);

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
    void updateEntityFromUpdateBO(@MappingTarget PublicEvent entity, PublicEventUpdateBO updateBO);

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
    PublicEvent toEntity(PublicEventCreateBO request);

    @Mapping(target="liked", ignore=true)
    @Mapping(target="subscribed", ignore=true)
    @Mapping(target = "author", ignore = true)
    @Mapping(target = "attachmentItems", ignore = true)
    @Mapping(target = "timelineItems", ignore = true)
    @Mapping(target = "sections", ignore = true)
    @Mapping(target = "actions", ignore = true)
    @Mapping(target = "contacts", ignore = true)
    @Mapping(target = "cardTimeline", ignore = true)
    PublicEventDetailBO toDetailBO(PublicEvent examInfo);

    PublicEventLegacyDetailVO toDetailVO(PublicEventDetailBO examDetailBO);

    PublicEventLegacyVO toVO(PublicEventDetailBO examDetailBO);

    List<PublicEventLegacyVO> toVOList(List<PublicEventDetailBO> examDetailBOList);
}





