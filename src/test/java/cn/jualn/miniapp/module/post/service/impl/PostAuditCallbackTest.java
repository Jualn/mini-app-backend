package cn.jualn.miniapp.module.post.service.impl;

import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.infrastructure.cache.RedisService;
import cn.jualn.miniapp.common.constant.RedisKeyConstant;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostAuditCallbackTest {

    @BeforeAll
    static void initMybatisMetadata() {
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, Post.class);
    }

    @Mock
    private PostMapper postMapper;
    @Mock
    private ContentAuditLogMapper contentAuditLogMapper;
    @Mock
    private RedisService redisService;

    @Test
    void onPass_shouldPublishWhenAllAuditLogsPassed() {
        PostAuditCallback callback = new PostAuditCallback(postMapper, contentAuditLogMapper, redisService);
        when(contentAuditLogMapper.selectCount(any())).thenReturn(1L, 0L, 1L);

        callback.onPass(7L, 1L);

        verify(postMapper).update(any(LambdaUpdateWrapper.class));
    }

    @Test
    void onReject_shouldMoveToManualReviewAndInvalidateCache() {
        PostAuditCallback callback = new PostAuditCallback(postMapper, contentAuditLogMapper, redisService);
        when(postMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(1);

        callback.onReject(7L, 1L, "bad");

        verify(postMapper).update(any(LambdaUpdateWrapper.class));
        verify(redisService).delete(RedisKeyConstant.postDetail(7L));
    }
}

