package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.ActivityStatus;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.enums.UserRole;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.activity.bo.ActivityCreateBO;
import cn.jualn.miniapp.module.activity.bo.ActivityDetailBO;
import cn.jualn.miniapp.module.activity.bo.ActivityUpdateBO;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.vo.ActivityDetailVO;
import cn.jualn.miniapp.module.interact.service.InteractService;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.service.MediaService;
import cn.jualn.miniapp.module.timeline.bo.TimelineItemBO;
import cn.jualn.miniapp.module.timeline.service.TimelineService;
import cn.jualn.miniapp.module.search.service.SearchService;
import cn.jualn.miniapp.module.user.bo.UserAuthBO;
import cn.jualn.miniapp.module.user.bo.UserSimpleBO;
import cn.jualn.miniapp.module.user.service.UserService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityServiceImplTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Activity.class);
    }

    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private ActivityConverter activityConverter;
    @Mock
    private ActivityEnrollmentService activityEnrollmentService;
    @Mock
    private MediaService mediaService;
    @Mock
    private UserService userService;
    @Mock
    private TimelineService timelineService;
    @Mock
    private RedisService redisService;
    @Mock
    private InteractService interactService;
    @Mock
    private SearchService searchService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createActivity_shouldCreateTimelineAndAttachments() {
        UserContext.setUserId(9L);
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, searchService);
        ActivityCreateBO command = ActivityCreateBO.builder()
                .timelineItems(List.of(TimelineItemBO.builder().label("t1").sortOrder(1).build()))
                .attachmentItems(List.of(AttachmentItemBO.builder().url("https://img").sortOrder(1).build()))
                .build();
        Activity entity = Activity.builder().id(66L).build();
        when(activityConverter.toEntity(command)).thenReturn(entity);

        Long id = service.createActivity(command);

        assertEquals(66L, id);
        verify(activityMapper).insert(entity);
        ArgumentCaptor<cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO> timelineCaptor = ArgumentCaptor.forClass(cn.jualn.miniapp.module.timeline.bo.TimelineSaveBO.class);
        verify(timelineService).replaceTimelines(timelineCaptor.capture());
        assertEquals(TargetType.ACTIVITY, timelineCaptor.getValue().getTargetType());
        assertEquals(66L, timelineCaptor.getValue().getTargetId());

        ArgumentCaptor<cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO> mediaCaptor = ArgumentCaptor.forClass(cn.jualn.miniapp.module.media.bo.MediaAttachmentSaveBO.class);
        verify(mediaService).replaceAttachments(mediaCaptor.capture());
        assertEquals(TargetType.ACTIVITY, mediaCaptor.getValue().getTargetType());
        assertEquals(66L, mediaCaptor.getValue().getTargetId());
    }

    @Test
    void getActivityDetail_shouldLoadAndEnrichWhenCacheMiss() {
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, searchService);
        Activity activity = Activity.builder().id(5L).userId(11L).build();
        ActivityDetailBO detailBO = ActivityDetailBO.builder().id(5L).userId(11L).build();
        ActivityDetailVO detailVO = ActivityDetailVO.builder().id(5L).build();

        when(redisService.get(RedisKeyConstant.activityDetail(5L), ActivityDetailBO.class)).thenReturn(null);
        when(activityMapper.selectByIdNotDeleted(5L)).thenReturn(activity);
        when(activityConverter.toDetailBO(activity)).thenReturn(detailBO);
        when(userService.getSimpleInfo(11L)).thenReturn(UserSimpleBO.builder().id(11L).nickname("author").build());
        when(mediaService.listAttachments(TargetType.ACTIVITY, 5L)).thenReturn(List.of());
        when(timelineService.listTimelinesByTarget(TargetType.ACTIVITY, 5L)).thenReturn(List.of());
        when(activityConverter.toDetailVO(detailBO)).thenReturn(detailVO);
        when(interactService.isLiked(TargetType.ACTIVITY, 5L)).thenReturn(true);
        when(activityEnrollmentService.isEnrolled(5L)).thenReturn(false);

        ActivityDetailVO result = service.getActivityDetail(5L);

        assertTrue(result.getLiked());
        assertFalse(result.getEnrolled());
        verify(redisService).set(RedisKeyConstant.activityDetail(5L), detailBO, RedisKeyConstant.ACTIVITY_DETAIL_TTL);
    }

    @Test
    void increaseCommentCount_shouldUpdateDbAndEvictDetailCache() {
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, searchService);

        service.increaseCommentCount(88L);

        verify(activityMapper).increaseCommentCount(88L);
        verify(redisService).delete(RedisKeyConstant.activityDetail(88L));
    }

    @Test
    void updateActivity_shouldAllowOwner() {
        UserContext.setUserId(9L);
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, searchService);
        Activity activity = Activity.builder().id(77L).userId(9L).build();
        ActivityUpdateBO command = ActivityUpdateBO.builder().id(77L).title("new").build();
        when(userService.getUserAuthInfo(9L)).thenReturn(UserAuthBO.builder().id(9L).role(UserRole.USER).build());
        when(activityMapper.selectOne(any())).thenReturn(activity);

        service.updateActivity(command);

        verify(activityConverter).updateEntityFromUpdateBO(activity, command);
        verify(activityMapper).updateById(activity);
        verify(redisService).delete(RedisKeyConstant.activityDetail(77L));
    }

    @Test
    void updateActivity_shouldThrowWhenUserIsNotOwner() {
        UserContext.setUserId(9L);
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, searchService);
        ActivityUpdateBO command = ActivityUpdateBO.builder().id(77L).build();
        when(userService.getUserAuthInfo(9L)).thenReturn(UserAuthBO.builder().id(9L).role(UserRole.USER).build());
        when(activityMapper.selectOne(any())).thenReturn(Activity.builder().id(77L).userId(10L).build());

        assertThrows(BusinessException.class, () -> service.updateActivity(command));

        verify(activityMapper, never()).updateById(any(Activity.class));
    }

    @Test
    void removeActivity_shouldAllowAdmin() {
        UserContext.setUserId(99L);
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, searchService);
        when(userService.getUserAuthInfo(99L)).thenReturn(UserAuthBO.builder().id(99L).role(UserRole.ADMIN).build());
        when(activityMapper.selectOne(any())).thenReturn(Activity.builder().id(88L).userId(11L).build());

        service.removeActivity(88L);

        ArgumentCaptor<Activity> activityCaptor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).updateById(activityCaptor.capture());
        assertEquals(ActivityStatus.DELETED.getCode(), activityCaptor.getValue().getStatus());
        verify(redisService).delete(RedisKeyConstant.activityDetail(88L));
        verify(redisService).delete(RedisKeyConstant.targetExists(TargetType.ACTIVITY.getKey(), 88L));
        verify(searchService).removeByTarget(TargetType.ACTIVITY, 88L);
    }

    @Test
    void removeActivity_shouldThrowWhenUserIsNotOwner() {
        UserContext.setUserId(9L);
        ActivityServiceImpl service = new ActivityServiceImpl(activityMapper, activityConverter, activityEnrollmentService,
                mediaService, userService, timelineService, redisService, interactService, searchService);
        when(userService.getUserAuthInfo(9L)).thenReturn(UserAuthBO.builder().id(9L).role(UserRole.USER).build());
        when(activityMapper.selectOne(any())).thenReturn(Activity.builder().id(88L).userId(10L).build());

        assertThrows(BusinessException.class, () -> service.removeActivity(88L));

        verify(activityMapper, never()).updateById(any(Activity.class));
    }
}




