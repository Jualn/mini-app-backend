package cn.jualn.miniapp.module.eventcontent.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.eventcontent.bo.*;
import cn.jualn.miniapp.module.eventcontent.entity.*;
import cn.jualn.miniapp.module.eventcontent.mapper.*;
import cn.jualn.miniapp.module.eventcontent.service.EventContentService;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.util.StringUtils;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventContentServiceImpl implements EventContentService {
    private final EventSectionMapper sectionMapper;
    private final EventActionMapper actionMapper;
    private final MediaService mediaService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String saveSections(TargetType type, Long id, List<EventSectionBO> sections, String legacyContent) {
        checkTarget(type, id);
        List<EventSection> old = sectionMapper.selectList(sectionQuery(type, id).orderByAsc(EventSection::getSortOrder, EventSection::getId));
        if (sections == null) {
            if (!old.isEmpty()) {
                String projection = project(old.stream().map(this::toBO).toList());
                if (legacyContent != null && !legacyContent.equals(projection)) {
                    // Only the single legacy INTRO is editable by old clients.
                    if (old.size() != 1 || !"INTRO".equals(old.get(0).getSectionType())) {
                        throw invalid("此内容包含多个详情段，请使用新版编辑器");
                    }
                    sections = List.of(EventSectionBO.builder().sectionType("INTRO")
                            .title(old.get(0).getTitle()).content(legacyContent).build());
                } else {
                    return projection;
                }
            } else {
                if (!StringUtils.hasText(legacyContent)) throw invalid("详情不能为空");
                sections = List.of(EventSectionBO.builder().sectionType("INTRO")
                        .title("介绍").content(legacyContent).build());
            }
        }
        if (sections.isEmpty() || sections.size() > 30) throw invalid("详情段数量必须为1至30");
        for (EventSectionBO s : sections) {
            if (s == null) throw invalid("详情段不能为空");
            text(s.getSectionType(), 32, true);
            if (!s.getSectionType().matches("[A-Z][A-Z0-9_]*")) throw invalid("详情段类型不合法");
            text(s.getTitle(), 128, true);
            text(s.getContent(), 20000, true);
        }
        String projection = project(sections);
        sectionMapper.delete(sectionQuery(type, id));
        for (int i = 0; i < sections.size(); i++) {
            EventSectionBO s = sections.get(i);
            sectionMapper.insert(EventSection.builder().targetType(type.getCode()).targetId(id)
                    .sectionType(s.getSectionType()).title(s.getTitle()).content(s.getContent())
                    .sortOrder(i).build());
        }
        return projection;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepareActions(TargetType type, Long id, List<EventActionBO> actions) {
        checkTarget(type, id);
        if (actions != null) {
            if (actions.size() > 20) throw invalid("参与入口最多20项");
            // Release replaced references before media diff; any later failure rolls back all changes.
            actionMapper.delete(actionQuery(type, id));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveActions(TargetType type, Long id, List<EventActionBO> actions) {
        checkTarget(type, id);
        if (actions == null) return;
        if (actions.size() > 20) throw invalid("参与入口最多20项");
        Map<Long, MediaAttachmentBO> media = mediaService.listAttachments(type, id).stream()
                .collect(Collectors.toMap(MediaAttachmentBO::getId, a -> a));
        for (int i = 0; i < actions.size(); i++) {
            EventActionBO a = actions.get(i);
            if (a == null || a.getActionType() == null || a.getActionType() < 1 || a.getActionType() > 8)
                throw invalid("参与入口类型不合法");
            text(a.getLabel(), 128, true);
            text(a.getDescription(), 2000, false);
            text(a.getTargetValue(), 1024, a.getActionType() <= 4);
            if (a.getActionType() == 1) {
                try {
                    URI uri = URI.create(a.getTargetValue());
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                        throw invalid("网页必须使用有效HTTPS地址");
                } catch (IllegalArgumentException e) { throw invalid("网页地址不合法"); }
            }
            if (a.getActionType() == 2 && !a.getTargetValue().matches("[0-9]{5,20}"))
                throw invalid("QQ群号不合法");
            if (a.getActionType() == 3 && !a.getTargetValue().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
                throw invalid("邮箱不合法");
            if (StringUtils.hasText(a.getAttachmentObjectKey())) {
                Long resolved = media.values().stream().filter(m -> a.getAttachmentObjectKey().equals(m.getObjectKey()))
                        .map(MediaAttachmentBO::getId).findFirst().orElseThrow(() -> invalid("附件对象键不属于当前事项"));
                if (a.getAttachmentId() != null && !resolved.equals(a.getAttachmentId())) throw invalid("附件ID与对象键不一致");
                a.setAttachmentId(resolved);
            }
            if (a.getAttachmentId() != null && !media.containsKey(a.getAttachmentId()))
                throw invalid("附件不属于当前事项");
            if (a.getActionType() == 5 || a.getActionType() == 6) {
                MediaAttachmentBO attachment = media.get(a.getAttachmentId());
                if (attachment == null || attachment.getType() == MediaType.URL
                        || (a.getActionType() == 5 && attachment.getType() != MediaType.IMAGE))
                    throw invalid("二维码或下载入口缺少正确类型的附件");
            }
            if (a.getActionType() == 7 && !StringUtils.hasText(a.getTargetValue()) && !StringUtils.hasText(a.getDescription()))
                throw invalid("线下提交需填写地址或说明");
            if (a.getActionType() == 8) text(a.getDescription(), 2000, true);
            actionMapper.insert(EventAction.builder().targetType(type.getCode()).targetId(id)
                    .actionType(a.getActionType()).label(a.getLabel()).description(a.getDescription())
                    .targetValue(a.getTargetValue()).attachmentId(a.getAttachmentId())
                    .isRequired(Boolean.TRUE.equals(a.getIsRequired())).sortOrder(i).build());
        }
    }

    @Override
    public List<EventSectionBO> sections(TargetType type, Long id, String legacyContent) {
        checkTarget(type, id);
        List<EventSectionBO> result = sectionMapper.selectList(sectionQuery(type, id)
                .orderByAsc(EventSection::getSortOrder, EventSection::getId)).stream().map(this::toBO).toList();
        return result.isEmpty() && StringUtils.hasText(legacyContent)
                ? List.of(EventSectionBO.builder().sectionType("INTRO").title("介绍").content(legacyContent).sortOrder(0).build())
                : result;
    }

    @Override
    public List<EventActionBO> actions(TargetType type, Long id) {
        checkTarget(type, id);
        return actionMapper.selectList(actionQuery(type, id).orderByAsc(EventAction::getSortOrder, EventAction::getId))
                .stream().map(a -> EventActionBO.builder().id(a.getId()).actionType(a.getActionType())
                        .label(a.getLabel()).description(a.getDescription()).targetValue(a.getTargetValue())
                        .attachmentId(a.getAttachmentId()).isRequired(a.getIsRequired()).sortOrder(a.getSortOrder()).build()).toList();
    }

    @Override
    public void validateCover(TargetType type, Long id, Long attachmentId) {
        checkTarget(type, id);
        if (attachmentId != null && mediaService.listAttachments(type, id).stream()
                .noneMatch(a -> attachmentId.equals(a.getId()) && a.getType() == MediaType.IMAGE))
            throw invalid("封面必须是当前事项的图片附件");
    }

    @Override
    public String auditText(TargetType type, Long id) {
        return actions(type, id).stream().map(a -> a.getLabel() + "\n"
                + Objects.toString(a.getDescription(), "") + "\n" + Objects.toString(a.getTargetValue(), ""))
                .collect(Collectors.joining("\n"));
    }

    private String project(List<EventSectionBO> sections) {
        if (sections.size() == 1 && "INTRO".equals(sections.get(0).getSectionType())) return sections.get(0).getContent();
        return sections.stream().map(s -> s.getTitle() + "\n" + s.getContent()).collect(Collectors.joining("\n\n"));
    }
    private EventSectionBO toBO(EventSection s) {
        return EventSectionBO.builder().id(s.getId()).sectionType(s.getSectionType()).title(s.getTitle())
                .content(s.getContent()).sortOrder(s.getSortOrder()).build();
    }
    private LambdaQueryWrapper<EventSection> sectionQuery(TargetType type, Long id) {
        return new LambdaQueryWrapper<EventSection>().eq(EventSection::getTargetType, type.getCode()).eq(EventSection::getTargetId, id);
    }
    private LambdaQueryWrapper<EventAction> actionQuery(TargetType type, Long id) {
        return new LambdaQueryWrapper<EventAction>().eq(EventAction::getTargetType, type.getCode()).eq(EventAction::getTargetId, id);
    }
    private void checkTarget(TargetType type, Long id) {
        if ((type != TargetType.ACTIVITY && type != TargetType.EXAM) || id == null) throw invalid("事项类型或ID不合法");
    }
    private void text(String value, int max, boolean required) {
        if ((required && !StringUtils.hasText(value)) || (value != null && value.codePointCount(0, value.length()) > max))
            throw invalid("内容为空或超过长度限制");
    }
    private BusinessException invalid(String message) { return new BusinessException(ResultCode.INVALID_OPERATION, message); }
}
