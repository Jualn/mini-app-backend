package cn.jualn.miniapp.module.exam.converter;
import cn.jualn.miniapp.module.exam.bo.*;
import cn.jualn.miniapp.module.exam.dto.admin.*;
import cn.jualn.miniapp.module.exam.vo.admin.*;
import org.mapstruct.*;
@Mapper(componentModel="spring", uses=ExamConverter.class)
public interface AdminPublicEventConverter {
    @Mapping(target="id", source="id")
    @Mapping(target="operatorId", source="operatorId")
    AdminPublicEventSaveBO toSaveBO(AdminPublicEventSaveRequest request, Long id, Long operatorId);
    @Mapping(target="sortOrder", ignore=true)
    cn.jualn.miniapp.module.timeline.bo.TimelineItemBO toTimelineBO(AdminPublicEventSaveRequest.Timeline item);
    AdminPublicEventQueryBO toQueryBO(AdminPublicEventPageQuery query);
    AdminPublicEventDetailVO toDetailVO(ExamDetailBO detail);
    AdminPublicEventPageVO toPageVO(AdminPublicEventPageBO page);
}
