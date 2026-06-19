package cn.jualn.miniapp.module.interact.service.impl;

import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import cn.jualn.miniapp.common.constant.UserContext;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.infrastructure.validator.TargetValidator;
import cn.jualn.miniapp.module.interact.entity.LikeRecord;
import cn.jualn.miniapp.module.interact.mapper.LikeRecordMapper;
import cn.jualn.miniapp.module.interact.mapper.ShareRecordMapper;
import cn.jualn.miniapp.module.interact.mapper.ViewLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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
    private TargetValidator targetValidator;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void like_shouldInsertAndUpdateCache() {
        UserContext.setUserId(8L);
        InteractServiceImpl service = new InteractServiceImpl(likeRecordMapper, shareRecordMapper, viewLogMapper, redisService, targetValidator);
        when(redisService.getLong(RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L))).thenReturn(5L);

        service.like(TargetType.POST, 100L);

        verify(likeRecordMapper).insert(any(LikeRecord.class));
        verify(redisService).set(RedisKeyConstant.likeStatus(8L, TargetType.POST.getKey(), 100L), "1", RedisKeyConstant.LIKE_STATUS_TTL);
        verify(redisService).incrementAndRefresh(RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L), RedisKeyConstant.LIKE_COUNT_TTL);
        verify(redisService).sAdd(RedisKeyConstant.LIKE_DIRTY_SET, RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L));
    }

    @Test
    void unlike_shouldDeleteAndUpdateCache() {
        UserContext.setUserId(8L);
        InteractServiceImpl service = new InteractServiceImpl(likeRecordMapper, shareRecordMapper, viewLogMapper, redisService, targetValidator);
        when(likeRecordMapper.delete(any())).thenReturn(1);
        when(redisService.getLong(RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L))).thenReturn(5L);

        service.unlike(TargetType.POST, 100L);

        verify(likeRecordMapper).delete(any());
        verify(redisService).setNullPlaceholder(RedisKeyConstant.likeStatus(8L, TargetType.POST.getKey(), 100L), RedisKeyConstant.LIKE_STATUS_TTL);
        verify(redisService).decrementAndRefresh(RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L), RedisKeyConstant.LIKE_COUNT_TTL);
        verify(redisService).sAdd(RedisKeyConstant.LIKE_DIRTY_SET, RedisKeyConstant.likeCount(TargetType.POST.getKey(), 100L));
    }

    @Test
    void getViewCount_shouldFallbackToDbAndCacheResult() {
        InteractServiceImpl service = new InteractServiceImpl(likeRecordMapper, shareRecordMapper, viewLogMapper, redisService, targetValidator);
        when(redisService.getLong(RedisKeyConstant.viewCount(TargetType.EXAM.getKey(), 200L))).thenReturn(null);
        when(viewLogMapper.selectCount(any())).thenReturn(3L);

        long count = service.getViewCount(TargetType.EXAM, 200L);

        assertEquals(3L, count);
        verify(redisService).set(RedisKeyConstant.viewCount(TargetType.EXAM.getKey(), 200L), 3L);
    }

    @Test
    void getShareCount_shouldReturnDbCount() {
        InteractServiceImpl service = new InteractServiceImpl(likeRecordMapper, shareRecordMapper, viewLogMapper, redisService, targetValidator);
        when(shareRecordMapper.selectCount(any())).thenReturn(2L);

        long count = service.getShareCount(TargetType.POST, 200L);

        assertEquals(2L, count);
    }
}
