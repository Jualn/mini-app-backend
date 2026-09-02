package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.activity.entity.Activity;
import cn.jualn.miniapp.module.activity.mapper.ActivityMapper;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityAuditCallbackTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Activity.class);
        TableInfoHelper.initTableInfo(assistant, ContentAuditLog.class);
    }

    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private ContentAuditLogMapper contentAuditLogMapper;
    @Mock
    private NotifyPlanMapper notifyPlanMapper;
    @Mock
    private NotifyService notifyService;

    @Test
    void onPass_shouldPublishAndCreateReminderWhenAllAuditLogsPassed() {
        ActivityAuditCallback callback = new ActivityAuditCallback(
                activityMapper, contentAuditLogMapper, notifyPlanMapper, notifyService);
        when(contentAuditLogMapper.selectCount(any())).thenReturn(1L);
        Activity activity = Activity.builder()
                .id(9L)
                .status(2)
                .title("t")
                .content("c")
                .startTime(LocalDateTime.now().plusHours(3))
                .build();
        when(activityMapper.selectById(any())).thenReturn(activity);
        doAnswer(invocation -> {
            NotifyPlan plan = invocation.getArgument(0);
            plan.setId(77L);
            return 1;
        }).when(notifyPlanMapper).insert(any(NotifyPlan.class));

        callback.onPass(9L);

        verifyUpdateCalled();
        verify(notifyPlanMapper).insert(any(NotifyPlan.class));
        verify(notifyService).enqueueNotifyPlan(77L);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaQueryWrapper> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(contentAuditLogMapper, times(2)).selectCount(queryCaptor.capture());
        boolean containsEnumParameter = queryCaptor.getAllValues().stream()
                .flatMap(wrapper -> wrapper.getParamNameValuePairs().values().stream())
                .anyMatch(TargetType.class::isInstance);
        assertFalse(containsEnumParameter);
    }

    @Test
    void onReject_shouldKeepActivityPendingForManualReview() {
        ActivityAuditCallback callback = new ActivityAuditCallback(
                activityMapper, contentAuditLogMapper, notifyPlanMapper, notifyService);
        when(activityMapper.update(any(LambdaUpdateWrapper.class))).thenReturn(1);

        callback.onReject(9L, "bad");

        verifyUpdateCalled();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void verifyUpdateCalled() {
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(activityMapper).update(captor.capture());
    }
}
