package cn.jualn.miniapp.module.post.service.impl;

import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.post.bo.PostSearchBO;
import cn.jualn.miniapp.module.post.converter.PostConverter;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.mapper.PostMapper;
import cn.jualn.miniapp.module.search.service.SearchService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostAuditCallbackTest {

    @Mock
    private PostMapper postMapper;
    @Mock
    private ContentAuditLogMapper contentAuditLogMapper;
    @Mock
    private SearchService searchService;
    @Mock
    private PostConverter postConverter;

    @Test
    void onPass_shouldSyncSearchIndexWhenAllAuditLogsPassed() {
        PostAuditCallback callback = new PostAuditCallback(postMapper, contentAuditLogMapper, searchService, postConverter);
        when(contentAuditLogMapper.selectCount(any())).thenReturn(1L);
        Post post = Post.builder().id(7L).status(2).auditStatus(1).content("hello").build();
        when(postMapper.selectById(any())).thenReturn(post);
        when(postConverter.toSearchBO(post)).thenReturn(PostSearchBO.builder().id(7L).content("hello").build());

        callback.onPass(7L);

        verify(postMapper).update(any(LambdaUpdateWrapper.class));
        verify(searchService).syncPost(any(PostSearchBO.class));
    }

    @Test
    void onReject_shouldRemoveSearchIndex() {
        PostAuditCallback callback = new PostAuditCallback(postMapper, contentAuditLogMapper, searchService, postConverter);

        callback.onReject(7L, "bad");

        verify(postMapper).update(any(LambdaUpdateWrapper.class));
        verify(searchService).removeByTarget(cn.jualn.miniapp.common.enums.TargetType.POST, 7L);
    }
}

