package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.bo.ActivitySearchBO;
import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.search.service.SearchService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityAuditCallbackTest {

    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private ContentAuditLogMapper contentAuditLogMapper;
    @Mock
    private SearchService searchService;
    @Mock
    private ActivityConverter activityConverter;

    @Test
    void onPass_shouldSyncSearchIndexWhenAllAuditLogsPassed() {
        ActivityAuditCallback callback = new ActivityAuditCallback(activityMapper, contentAuditLogMapper, searchService, activityConverter);
        when(contentAuditLogMapper.selectCount(any())).thenReturn(1L);
        Activity activity = Activity.builder().id(9L).status(2).title("t").content("c").build();
        when(activityMapper.selectById(any())).thenReturn(activity);
        when(activityConverter.toSearchBO(activity)).thenReturn(ActivitySearchBO.builder().id(9L).title("t").content("c").build());

        callback.onPass(9L);

        verifyUpdateCalled();
        verify(searchService).syncActivity(any(ActivitySearchBO.class));
    }

    @Test
    void onReject_shouldRemoveSearchIndex() {
        ActivityAuditCallback callback = new ActivityAuditCallback(activityMapper, contentAuditLogMapper, searchService, activityConverter);

        callback.onReject(9L, "bad");

        verifyUpdateCalled();
        verify(searchService).removeByTarget(cn.jualn.miniapp.common.enums.TargetType.ACTIVITY, 9L);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void verifyUpdateCalled() {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(activityMapper).update(captor.capture());
    }
}






