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
    public void saveSections(TargetType type, Long id, List<EventSectionBO> sections) {
        checkTarget(type, id);
        if (sections == null) throw invalid("详情段不能缺失");
        if (sections.size() > 50) throw invalid("详情段最多50项");
        for (EventSectionBO s : sections) {
            if (s == null) throw invalid("详情段不能为空");
            text(s.getSectionKey(), 128, true);
            text(s.getTitle(), 120, true);
            text(s.getContent(), 50000, true);
        }
        sectionMapper.delete(sectionQuery(type, id));
        for (int i = 0; i < sections.size(); i++) {
            EventSectionBO s = sections.get(i);
            sectionMapper.insert(EventSection.builder().targetType(type.getCode()).targetId(id)
                    .sectionKey(s.getSectionKey())
                    .contentFormat(s.getContentFormat() == null ? 0 : s.getContentFormat())
                    .title(s.getTitle()).content(s.getContent())
                    .sortOrder(s.getSortOrder() == null ? i : s.getSortOrder()).build());
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void prepareActions(TargetType type, Long id, List<EventActionBO> actions) {
        checkTarget(type, id);
        if (actions != null) {
            if (actions.size() > 50) throw invalid("参与入口最多50项");
            // Release replaced references before media diff; any later failure rolls back all changes.
            actionMapper.delete(actionQuery(type, id));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveActions(TargetType type, Long id, List<EventActionBO> actions) {
        checkTarget(type, id);
        if (actions == null) return;
        if (actions.size() > 50) throw invalid("参与入口最多50项");
        Map<Long, MediaAttachmentBO> media = mediaService.listAttachments(type, id).stream()
                .collect(Collectors.toMap(MediaAttachmentBO::getId, a -> a));
        for (int i = 0; i < actions.size(); i++) {
            EventActionBO a = actions.get(i);
            if (a == null || a.getActionType() == null || a.getActionType() < 1 || a.getActionType() > 8)
                throw invalid("参与入口类型不合法");
            text(a.getActionKey(), 128, true);
            text(a.getLabel(), 120, true);
            text(a.getDescription(), 2000, false);
            text(a.getTargetValue(), 1024, false);
            if (!StringUtils.hasText(a.getDescription()) && !StringUtils.hasText(a.getTargetValue())
                    && a.getAttachmentId() == null && !StringUtils.hasText(a.getAttachmentObjectKey()))
                throw invalid("参与入口至少需要说明、地址或附件之一");
            if (StringUtils.hasText(a.getTargetValue())) {
                try {
                    URI uri = URI.create(a.getTargetValue());
                    if (!Set.of("http", "https", "mailto").contains(uri.getScheme())) throw invalid("参与入口地址协议不合法");
                } catch (IllegalArgumentException e) { throw invalid("网页地址不合法"); }
            }
            if (Set.of(1, 4, 7).contains(a.getActionType())
                    && (!StringUtils.hasText(a.getTargetValue()) || !a.getTargetValue().matches("^https?://.+")))
                throw invalid("该参与入口需要HTTP(S)地址");
            if (a.getActionType() == 3 && (!StringUtils.hasText(a.getTargetValue()) || !a.getTargetValue().startsWith("mailto:")))
                throw invalid("邮件提交需要mailto地址");
            if (StringUtils.hasText(a.getAttachmentObjectKey())) {
                Long resolved = media.values().stream().filter(m -> a.getAttachmentObjectKey().equals(m.getObjectKey()))
                        .map(MediaAttachmentBO::getId).findFirst().orElseThrow(() -> invalid("附件对象键不属于当前事项"));
                if (a.getAttachmentId() != null && !resolved.equals(a.getAttachmentId())) throw invalid("附件ID与对象键不一致");
                a.setAttachmentId(resolved);
            }
            if (a.getAttachmentId() != null && !media.containsKey(a.getAttachmentId()))
                throw invalid("附件不属于当前事项");
            if ((a.getActionType() == 5 || a.getActionType() == 6) && a.getAttachmentId() == null
                    && (!StringUtils.hasText(a.getTargetValue()) || !a.getTargetValue().matches("^https?://.+")))
                throw invalid("查看或下载入口需要附件或HTTP(S)地址");
            actionMapper.insert(EventAction.builder().targetType(type.getCode()).targetId(id)
                    .actionKey(a.getActionKey())
                    .actionType(a.getActionType()).label(a.getLabel()).description(a.getDescription())
                    .targetValue(a.getTargetValue()).attachmentId(a.getAttachmentId())
                    .isRequired(Boolean.TRUE.equals(a.getIsRequired()))
                    .sortOrder(a.getSortOrder() == null ? i : a.getSortOrder()).build());
        }
    }

    @Override
    public List<EventSectionBO> sections(TargetType type, Long id) {
        checkTarget(type, id);
        return sectionMapper.selectList(sectionQuery(type, id)
                .orderByAsc(EventSection::getSortOrder, EventSection::getId)).stream().map(this::toBO).toList();
    }

    @Override
    public List<EventActionBO> actions(TargetType type, Long id) {
        checkTarget(type, id);
        return actionMapper.selectList(actionQuery(type, id).orderByAsc(EventAction::getSortOrder, EventAction::getId))
                .stream().map(a -> EventActionBO.builder().id(a.getId()).actionKey(a.getActionKey()).actionType(a.getActionType())
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

    private EventSectionBO toBO(EventSection s) {
        return EventSectionBO.builder().id(s.getId()).sectionKey(s.getSectionKey()).contentFormat(s.getContentFormat()).title(s.getTitle())
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
