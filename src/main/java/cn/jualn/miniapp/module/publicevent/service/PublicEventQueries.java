package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.module.publicevent.enums.PublicEventStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.PageResult;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.publicevent.entity.PublicEvent;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventDetailBO;
import cn.jualn.miniapp.module.publicevent.bo.PublicEventPageBO;
import cn.jualn.miniapp.module.publicevent.converter.PublicEventConverter;
import cn.jualn.miniapp.module.publicevent.service.PublicEventSubscriptionService;
import cn.jualn.miniapp.module.publicevent.service.PublicEventCursorCodec;
import cn.jualn.miniapp.module.interact.service.InteractionLikeQueries;
import cn.jualn.miniapp.module.timeline.service.CardTimelinePolicy;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 公开公共事项查询；包内详情组装不补充个人互动状态。 */
@Service
@RequiredArgsConstructor
public class PublicEventQueries {
    private final cn.jualn.miniapp.module.eventcontent.service.EventContentService eventContentService;
    private final PublicEventMapper examInfoMapper;
    private final PublicEventConverter examConverter;
    private final cn.jualn.miniapp.module.media.service.MediaQueries mediaQueries;
    private final TimelineService timelineService;
    private final InteractionLikeQueries interactService;
    private final PublicEventSubscriptionService examSubscriptionService;
    private final cn.jualn.miniapp.module.eventcontent.service.EventContactCodec eventContactCodec;

    /** 当前发布资格下的公共详情及联系人表示。 */
    public PublicEventDetailBO getPublicEventResource(Long id) {
        PublicEventDetailBO detail = getExamDetail(id);
        detail.setContacts(eventContactCodec.read(detail.getContactsJson(), null, null, null));
        return detail;
    }

    /**
     * 获取考试详情。
     *
     * @param id 考试ID
     * @return 考试详情
     * @throws BusinessException 考试不存在或无权限时抛出异常
     */
    public PublicEventDetailBO getExamDetail(Long id) {
        PublicEvent exam = requireVisibleExam(id);
        PublicEventDetailBO detail = loadPublicEventDetail(exam);
        Long userId = UserContext.getUserId();
        detail.setLiked(userId != null && interactService.isLiked(TargetType.EXAM, id));
        detail.setSubscribed(userId != null && examSubscriptionService.isSubscribed(id));
        return detail;
    }

    /**
     * 考试分页列表（游标分页）。
     *
     * @param command 查询业务对象
     * @return 分页结果
     */
    public PageResult<PublicEventDetailBO> pageExam(PublicEventPageBO command) {
        int pageSize = command.getPageSize() == null ? 20 : command.getPageSize();
        Integer status = command.getStatus();
        if (status == null || !Objects.equals(status, PublicEventStatus.PUBLISHED.getCode())) {
            status = PublicEventStatus.PUBLISHED.getCode();
        }

        String keyword = command.getKeyword();
        if (keyword != null && keyword.isBlank()) {
            keyword = null;
        }

        // 多取一条用于判断是否还有下一页。
        List<PublicEvent> examInfos = examInfoMapper.selectPageExams(
                status,
                command.getCategory(),
                command.getEventType(),
                command.getLifecycleStatus(),
                keyword,
                command.getLastId(),
                pageSize + 1
        );
        boolean hasMore = examInfos.size() > pageSize;
        if (hasMore) {
            examInfos = examInfos.subList(0, pageSize);
        }

        List<PublicEventDetailBO> list = examConverter.toDetailList(examInfos);
        Map<Long, List<cn.jualn.miniapp.module.timeline.bo.TimelineFactBO>> timelines =
                timelineService.listTimelinesByTargets(TargetType.EXAM, list.stream().map(PublicEventDetailBO::getId).toList());
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        for (PublicEventDetailBO item : list) {
            item.setCardTimeline(CardTimelinePolicy.select(
                    timelines.getOrDefault(item.getId(), List.of()), now));
        }
        return PageResult.of(list, hasMore, list.isEmpty() ? null : list.get(list.size() - 1).getId());
    }

    public cn.jualn.miniapp.module.publicevent.bo.PublicEventResourcePageBO pagePublicEventResources(PublicEventPageBO query) {
        query.setLastId(PublicEventCursorCodec.decode(query));
        PageResult<PublicEventDetailBO> page = pageExam(query);
        String nextCursor = Boolean.TRUE.equals(page.getHasMore())
                ? PublicEventCursorCodec.encode(query, page.getNextCursor()) : null;
        return new cn.jualn.miniapp.module.publicevent.bo.PublicEventResourcePageBO(page.getList(), nextCursor);
    }

    PublicEventDetailBO loadPublicEventDetail(PublicEvent exam) {
        PublicEventDetailBO detail = examConverter.toDetailBO(exam);
        detail.setAttachmentItems(mediaQueries.listAttachments(TargetType.EXAM, exam.getId()));
        detail.setTimelineItems(timelineService.listTimelinesByTarget(TargetType.EXAM, exam.getId()));
        detail.setCardTimeline(CardTimelinePolicy.select(detail.getTimelineItems(),
                LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))));
        detail.setSections(eventContentService.sections(TargetType.EXAM, exam.getId()));
        detail.setActions(eventContentService.actions(TargetType.EXAM, exam.getId()));
        return detail;
    }

    private PublicEvent requireVisibleExam(Long examId) {
        PublicEvent examInfo = requireExam(examId);
        cn.jualn.miniapp.module.publicevent.service.PublicEventPublicAccess.requirePublished(examInfo);
        return examInfo;
    }

    private PublicEvent requireExam(Long examId) {
        PublicEvent examInfo = examInfoMapper.selectByIdNotDeleted(examId);
        if (examInfo == null) {
            throw new BusinessException(ResultCode.EXAM_NOT_FOUND);
        }
        return examInfo;
    }
}
