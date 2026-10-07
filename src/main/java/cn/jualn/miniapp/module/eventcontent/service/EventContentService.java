package cn.jualn.miniapp.module.eventcontent.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.eventcontent.bo.EventActionBO;
import cn.jualn.miniapp.module.eventcontent.bo.EventSectionBO;
import java.util.List;

/** Called inside the owning activity/exam transaction, after its permission checks. */
public interface EventContentService {
    void saveSections(TargetType type, Long id, List<EventSectionBO> sections);
    void prepareActions(TargetType type, Long id, List<EventActionBO> actions);
    void saveActions(TargetType type, Long id, List<EventActionBO> actions);
    List<EventSectionBO> sections(TargetType type, Long id);
    List<EventActionBO> actions(TargetType type, Long id);
    void validateCover(TargetType type, Long id, Long attachmentId);
    String auditText(TargetType type, Long id);
}
