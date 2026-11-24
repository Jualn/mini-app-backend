package cn.jualn.miniapp.module.interact.service.impl;

import cn.jualn.miniapp.module.interact.service.InteractionTestServices;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.module.subject.service.SubjectExistenceService;
import cn.jualn.miniapp.module.interact.entity.LikeRecord;
import cn.jualn.miniapp.module.interact.mapper.LikeRecordMapper;
import cn.jualn.miniapp.module.interact.mapper.ShareRecordMapper;
import cn.jualn.miniapp.module.interact.mapper.ViewLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InteractServiceImplTest {

    @Mock
    private LikeRecordMapper likeRecordMapper;
    @Mock
    private ShareRecordMapper shareRecordMapper;
    @Mock
    private ViewLogMapper viewLogMapper;
    @Mock
    private RedisService redisService;
    @Mock
    private SubjectExistenceService targetValidator;
    @Mock private cn.jualn.miniapp.module.interact.service.InteractionSubjects subjects;
    @Mock private cn.jualn.miniapp.module.interact.service.InteractionSubject examSubject;
    @Mock private cn.jualn.miniapp.module.post.service.PostNotificationFactsService postNotificationFacts;
    @Mock private cn.jualn.miniapp.module.comment.service.CommentNotificationFactsService commentNotificationFacts;
    @Mock private cn.jualn.miniapp.module.user.service.UserService userService;
    @Mock private cn.jualn.miniapp.module.user.service.UserAccountService userAccounts;
    @Mock private cn.jualn.miniapp.module.notify.service.NotifyService notifyService;
    @Mock private cn.jualn.miniapp.module.article.service.ArticleService articleService;
    @Mock private cn.jualn.miniapp.module.comment.service.CommentTargetAccess commentTargetAccess;
    @Mock private cn.jualn.miniapp.module.comment.service.ArticleCommentNotifications articleCommentNotifications;
    @InjectMocks private InteractionTestServices service;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void like_shouldInsertAndUpdateCache() {
        UserContext.setUserId(8L);
        when(likeRecordMapper.insert(any(LikeRecord.class))).thenReturn(1);
        when(redisService.getLong(RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L))).thenReturn(5L);

        service.likes().like(TargetType.POST, 100L);

        verify(likeRecordMapper).insert(any(LikeRecord.class));
        verify(redisService).set(RedisKeyConstant.likeStatus(8L, TargetType.POST.getKey(), 100L), "1", RedisKeyConstant.LIKE_STATUS_TTL);
        verify(redisService).incrementAndRefresh(RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L), RedisKeyConstant.LIKE_COUNT_TTL);
        verify(redisService).sAdd(RedisKeyConstant.LIKE_DIRTY_SET, RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L));
    }

    @Test
    void unlike_shouldDeleteAndUpdateCache() {
        UserContext.setUserId(8L);
        when(likeRecordMapper.delete(any())).thenReturn(1);
        when(redisService.getLong(RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L))).thenReturn(5L);

        service.likes().unlike(TargetType.POST, 100L);

        verify(likeRecordMapper).delete(any());
        verify(redisService).setNullPlaceholder(RedisKeyConstant.likeStatus(8L, TargetType.POST.getKey(), 100L), RedisKeyConstant.LIKE_STATUS_TTL);
        verify(redisService).decrementAndRefresh(RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L), RedisKeyConstant.LIKE_COUNT_TTL);
        verify(redisService).sAdd(RedisKeyConstant.LIKE_DIRTY_SET, RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L));
    }

    @Test
    void getViewCount_shouldCombinePersistedCountAndPendingDelta() {
        when(redisService.getLong(RedisKeyConstant.viewDelta(TargetType.EXAM.getKey(), 200L))).thenReturn(2L);
        when(subjects.require(TargetType.EXAM)).thenReturn(examSubject);
        when(examSubject.viewCount(200L)).thenReturn(3L);

        long count = service.counts().getViewCount(TargetType.EXAM, 200L);

        assertEquals(5L, count);
        verify(commentTargetAccess).requireSubject(TargetType.EXAM, 200L, false);
    }

    @Test
    void getShareCount_shouldReturnDbCount() {
        when(shareRecordMapper.selectCount(any())).thenReturn(2L);

        long count = service.counts().getShareCount(TargetType.POST, 200L);

        assertEquals(2L, count);
    }

    @Test
    void hiddenSubjectCannotReceiveLikesOrCacheEffects() {
        UserContext.setUserId(8L);
        org.mockito.Mockito.doThrow(new cn.jualn.miniapp.common.exception.BusinessException(
                cn.jualn.miniapp.common.result.ResultCode.FORBIDDEN))
                .when(commentTargetAccess).requireSubject(TargetType.POST, 100L, true);
        org.junit.jupiter.api.Assertions.assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,
                () -> service.likes().like(TargetType.POST, 100L));
        org.mockito.Mockito.verifyNoInteractions(likeRecordMapper, redisService);
    }

    @Test
    void bannedUserCannotReachSubjectOrWriteInteraction() {
        UserContext.setUserId(8L);
        org.mockito.Mockito.doThrow(new cn.jualn.miniapp.common.exception.BusinessException(
                cn.jualn.miniapp.common.result.ResultCode.USER_BANNED)).when(userAccounts).assertLoginAllowed(8L);
        org.junit.jupiter.api.Assertions.assertThrows(cn.jualn.miniapp.common.exception.BusinessException.class,
                () -> service.likes().like(TargetType.POST, 100L));
        org.mockito.Mockito.verifyNoInteractions(commentTargetAccess, likeRecordMapper, redisService);
    }
}
