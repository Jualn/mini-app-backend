package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.dto.request.ActivityCreateRequest;
import cn.jualn.miniapp.module.activity.dto.request.ActivityUpdateRequest;
import cn.jualn.miniapp.module.activity.dto.request.EventActionRequest;
import cn.jualn.miniapp.module.activity.dto.request.EventSectionRequest;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.vo.EventActionVO;
import cn.jualn.miniapp.module.activity.vo.EventSectionVO;
import cn.jualn.miniapp.module.timeline.converter.TimelineConverter;
import cn.jualn.miniapp.common.mapper.EnumConverter;
import cn.jualn.miniapp.module.activity.vo.ActivityDetailVO;
import org.mapstruct.*;

import java.util.List;

/**
 * 活动对象转换器。
 */
@Mapper(componentModel = "spring", uses = {TimelineConverter.class, EnumConverter.class, ActivityEnumMappings.class})
public interface ActivityConverter {
    cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO toEventSectionBO(EventSectionRequest request);

    EventSectionVO toEventSectionVO(cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO bo);

    List<EventSectionVO> toEventSectionVOs(java.util.List<cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO> bo);

    cn.jualn.miniapp.module.eventcontent.bo.EventActionBO toEventActionBO(EventActionRequest request);

    EventActionVO toEventActionVO(cn.jualn.miniapp.module.eventcontent.bo.EventActionBO bo);

    List<EventActionVO> toEventActionVOs(List<cn.jualn.miniapp.module.eventcontent.bo.EventActionBO> bo);


    ActivityCreateBO toCreateBO(ActivityCreateRequest request);

    ActivityUpdateBO toUpdateBO(ActivityUpdateRequest request);

    /**
     * 将更新参数应用到活动对象。
     *
     * <p>该方法实现部分更新逻辑：只更新BO对象中非null的字段到Entity对象。</p>
     *
     * @param entity   待更新的活动Entity对象
     * @param updateBO 包含更新值的BO对象（null字段将被忽略）
     */
    @Mapping(target = "formSchema", ignore = true)
    @Mapping(target = "cancelledAt", ignore = true)
    @Mapping(target = "cancelReason", ignore = true)
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
    @Mapping(target = "summary", ignore = true)
    @Mapping(target = "audienceSummary", ignore = true)
    @Mapping(target = "registrationMode", ignore = true)
    @Mapping(target = "participantMode", ignore = true)
    @Mapping(target = "capacity", ignore = true)
    @Mapping(target = "capacityUnit", ignore = true)
    @Mapping(target = "coverAttachmentId", ignore = true)
    void updateEntityFromUpdateBO(@MappingTarget Activity entity, ActivityUpdateBO updateBO);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "formSchema", ignore = true)
    @Mapping(target = "cancelledAt", ignore = true)
    @Mapping(target = "cancelReason", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "publishStatus", ignore = true)
    @Mapping(target = "commentCount", ignore = true)
    @Mapping(target = "likeCount", ignore = true)
    @Mapping(target = "viewCount", ignore = true)
    @Mapping(target = "publishedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "summary", ignore = true)
    @Mapping(target = "audienceSummary", ignore = true)
    @Mapping(target = "registrationMode", ignore = true)
    @Mapping(target = "participantMode", ignore = true)
    @Mapping(target = "capacity", ignore = true)
    @Mapping(target = "capacityUnit", ignore = true)
    @Mapping(target = "coverAttachmentId", ignore = true)
    Activity toEntity(ActivityCreateBO request);

    @Mapping(target = "formSchema", ignore = true)
    @Mapping(target = "cancelledAt", ignore = true)
    @Mapping(target = "cancelReason", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "publishStatus", ignore = true)
    @Mapping(target = "commentCount", ignore = true)
    @Mapping(target = "likeCount", ignore = true)
    @Mapping(target = "viewCount", ignore = true)
    @Mapping(target = "publishedAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    @Mapping(target = "summary", ignore = true)
    @Mapping(target = "audienceSummary", ignore = true)
    @Mapping(target = "registrationMode", ignore = true)
    @Mapping(target = "participantMode", ignore = true)
    @Mapping(target = "capacity", ignore = true)
    @Mapping(target = "capacityUnit", ignore = true)
    @Mapping(target = "coverAttachmentId", ignore = true)
    Activity toEntity(ActivityUpdateBO request);

    @Mapping(target = "author", ignore = true)
    @Mapping(target = "attachmentItems", ignore = true)
    @Mapping(target = "timelineItems", ignore = true)
    @Mapping(target = "activityPhase", ignore = true)
    @Mapping(target = "registrationStatus", ignore = true)
    @Mapping(target = "sections", ignore = true)
    @Mapping(target = "actions", ignore = true)
    @Mapping(target = "liked", ignore = true)
    @Mapping(target = "enrolled", ignore = true)
    @Mapping(target = "contacts", ignore = true)
    @Mapping(target = "registrationForm", ignore = true)
    @Mapping(target = "participationState", ignore = true)
    @Mapping(target = "submittedCount", ignore = true)
    @Mapping(target = "evaluatedAt", ignore = true)
    @Mapping(target = "cardTimeline", ignore = true)
    ActivityDetailBO toDetailBO(Activity activity);


    ActivityDetailVO toDetailVO(ActivityDetailBO detailBO);

    @Mapping(target = "participationState", ignore = true)
    @Mapping(target = "submittedCount", ignore = true)
    @Mapping(target = "evaluatedAt", ignore = true)
    @Mapping(target = "coverAttachment", ignore = true)
    @Mapping(target = "cardTimeline", ignore = true)
    ActivityListBO toListBO(Activity activity);

    List<ActivityListBO> toListBOList(List<Activity> activities);


    ActivitySearchBO toSearchBO(Activity activity);
}
