package cn.jualn.miniapp.module.activity.converter;

import cn.jualn.miniapp.module.activity.bo.*;
import cn.jualn.miniapp.module.activity.dto.request.ActivityCreateRequest;
import cn.jualn.miniapp.module.activity.dto.request.ActivityPageQuery;
import cn.jualn.miniapp.module.activity.dto.request.ActivityUpdateRequest;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.timeline.converter.TimelineConverter;
import cn.jualn.miniapp.common.mapper.EnumConverter;
import cn.jualn.miniapp.module.activity.vo.ActivityDetailVO;
import cn.jualn.miniapp.module.activity.vo.ActivityListVO;
import org.mapstruct.*;

import java.util.List;

/**
 * 活动对象转换器。
 */
@Mapper(componentModel = "spring", uses = {TimelineConverter.class, EnumConverter.class})
public interface ActivityConverter {

    ActivityCreateBO toCreateBO(ActivityCreateRequest request);

    ActivityUpdateBO toUpdateBO(ActivityUpdateRequest request);

    ActivityPageBO toPageBO(ActivityPageQuery query);

    /**
     * 将更新参数应用到活动对象。
     *
     * <p>该方法实现部分更新逻辑：只更新BO对象中非null的字段到Entity对象。</p>
     *
     * @param entity 待更新的活动Entity对象
     * @param updateBO  包含更新值的BO对象（null字段将被忽略）
     */
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
    void updateEntityFromUpdateBO(@MappingTarget Activity entity, ActivityUpdateBO updateBO);

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
    Activity toEntity(ActivityCreateBO request);

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
    Activity toEntity(ActivityUpdateBO request);

    @Mapping(target = "author", ignore = true)
    @Mapping(target = "attachmentItems", ignore = true)
    @Mapping(target = "timelineItems", ignore = true)
    ActivityDetailBO toDetailBO(Activity activity);

    ActivityListVO toVO(ActivityDetailBO detailBO);

    @Mapping(target = "liked", ignore = true)
    @Mapping(target = "enrolled", ignore = true)
    ActivityDetailVO toDetailVO(ActivityDetailBO detailBO);

    List<ActivityListBO> toListBOList(List<Activity> activities);

    List<ActivityListVO> toVOList(List<ActivityListBO> detailVOList);

    ActivitySearchBO toSearchBO(Activity activity);
}