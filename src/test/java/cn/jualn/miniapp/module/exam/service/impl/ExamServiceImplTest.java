package cn.jualn.miniapp.module.exam.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.ExamStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.exam.bo.ExamDetailBO;
import cn.jualn.miniapp.module.exam.bo.ExamPageBO;
import cn.jualn.miniapp.module.exam.converter.ExamConverter;
import cn.jualn.miniapp.module.exam.entity.ExamInfo;
import cn.jualn.miniapp.module.exam.mapper.ExamInfoMapper;
import cn.jualn.miniapp.module.exam.mapper.ExamSubscriptionMapper;
import cn.jualn.miniapp.module.exam.vo.ExamDetailVO;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.user.bo.UserAuthBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExamServiceImplTest {

    @Mock
    private ExamInfoMapper examInfoMapper;
    @Mock
    private ExamSubscriptionMapper examSubscriptionMapper;
    @Mock
    private ExamConverter examConverter;
    @Mock
    private MediaService mediaService;
    @Mock
    private TimelineService timelineService;
    @Mock
    private UserService userService;
    @Mock
    private RedisService redisService;
    @Mock
    private InteractService interactService;
    @Mock
    private ExamSubscriptionServiceImpl examSubscriptionService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void pageExam_shouldDefaultToPublishedAndSetNextCursor() {
        ExamServiceImpl service = new ExamServiceImpl(
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, timelineService, userService, redisService, interactService, examSubscriptionService);

        ExamInfo first = ExamInfo.builder().id(20L).userId(7L).status(ExamStatus.PUBLISHED.getCode()).build();
        ExamInfo second = ExamInfo.builder().id(10L).userId(8L).status(ExamStatus.PUBLISHED.getCode()).build();
        when(examInfoMapper.selectPageExams(ExamStatus.PUBLISHED.getCode(), 3, null, 50L, 2)).thenReturn(List.of(first, second));
        when(examConverter.toDetailList(any())).thenReturn(List.of(
                ExamDetailBO.builder().id(20L).build(),
                ExamDetailBO.builder().id(10L).build()
        ));

        ExamPageBO query = ExamPageBO.builder()
                .pageSize(1)
                .status(null)
                .category(3)
                .lastId(50L)
                .keyword("   ")
                .build();

        var result = service.pageExam(query);

        assertEquals(10L, result.getNextCursor());
        verify(examInfoMapper).selectPageExams(ExamStatus.PUBLISHED.getCode(), 3, null, 50L, 2);
    }

    @Test
    void getExamDetail_shouldUseCacheWhenPresent() {
        UserContext.setUserId(99L);
        ExamServiceImpl service = new ExamServiceImpl(
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, timelineService, userService, redisService, interactService, examSubscriptionService);

        ExamDetailBO cached = ExamDetailBO.builder().id(88L).title("cached").build();
        when(redisService.get(RedisKeyConstant.examDetail(88L), ExamDetailBO.class)).thenReturn(cached);
        when(examConverter.toDetailVO(cached)).thenReturn(new ExamDetailVO());
        when(interactService.isLiked(TargetType.EXAM, 88L)).thenReturn(true);
        when(examSubscriptionService.isSubscribed(88L)).thenReturn(true);

        ExamDetailBO result = service.getExamDetail(88L);

        assertEquals(cached, result);
        verify(examInfoMapper, never()).selectByIdNotDeleted(any());
        verify(interactService).isLiked(TargetType.EXAM, 88L);
        verify(examSubscriptionService).isSubscribed(88L);
    }

    @Test
    void getExamDetail_shouldLoadAndReturnDetailWhenCacheMiss() {
        UserContext.setUserId(99L);
        ExamServiceImpl service = new ExamServiceImpl(
                examInfoMapper, examSubscriptionMapper, examConverter, mediaService, timelineService, userService, redisService, interactService, examSubscriptionService);

        ExamInfo examInfo = ExamInfo.builder().id(88L).userId(20L).status(ExamStatus.PUBLISHED.getCode()).build();
        ExamDetailBO detailBO = ExamDetailBO.builder().id(88L).build();
        when(redisService.get(RedisKeyConstant.examDetail(88L), ExamDetailBO.class)).thenReturn(null);
        when(examInfoMapper.selectByIdNotDeleted(88L)).thenReturn(examInfo);
        when(examConverter.toDetailBO(examInfo)).thenReturn(detailBO);
        when(examConverter.toDetailVO(detailBO)).thenReturn(new ExamDetailVO());
        when(userService.getSimpleInfo(20L)).thenReturn(UserSimpleBO.builder().id(20L).nickname("author").build());
        when(mediaService.listAttachments(TargetType.EXAM, 88L)).thenReturn(List.of());
        when(timelineService.listTimelinesByTarget(TargetType.EXAM, 88L)).thenReturn(List.of());
        when(interactService.isLiked(TargetType.EXAM, 88L)).thenReturn(false);
        when(examSubscriptionService.isSubscribed(88L)).thenReturn(false);

        ExamDetailBO result = service.getExamDetail(88L);

        assertEquals(detailBO, result);
        verify(userService).getSimpleInfo(20L);
        verify(mediaService).listAttachments(TargetType.EXAM, 88L);
        verify(timelineService).listTimelinesByTarget(TargetType.EXAM, 88L);
    }

}


