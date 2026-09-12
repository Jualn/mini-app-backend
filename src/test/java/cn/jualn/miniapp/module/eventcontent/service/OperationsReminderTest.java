package cn.jualn.miniapp.module.eventcontent.service;
import cn.jualn.miniapp.module.notify.service.impl.NotifyServiceImpl;
import cn.jualn.miniapp.module.notify.mapper.NotifyPlanMapper;
import cn.jualn.miniapp.module.notify.entity.NotifyPlan;
import cn.jualn.miniapp.infrastructure.queue.contract.DelayQueueProducer;
import cn.jualn.miniapp.common.enums.TargetType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class OperationsReminderTest {
    @Mock NotifyPlanMapper mapper;
    @Mock DelayQueueProducer producer;
    @InjectMocks NotifyServiceImpl service;
    @BeforeEach void setup() {
        TransactionSynchronizationManager.initSynchronization();
        var assistant=new org.apache.ibatis.builder.MapperBuilderAssistant(new com.baomidou.mybatisplus.core.MybatisConfiguration(),"");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,NotifyPlan.class);
    }
    @AfterEach void clear() {TransactionSynchronizationManager.clearSynchronization();}
    @Test void noTimeOnlyCancelsPendingPlans() {
        service.replaceEventReminder(TargetType.EXAM,7L,"事项",null);
        verify(mapper).cancelBySource(2,7L);verify(mapper,never()).insert(any(NotifyPlan.class));
        verifyNoInteractions(producer);
    }
    @Test void sentTimeIsNotRecreated() {
        when(mapper.exists(any())).thenReturn(true);
        service.replaceEventReminder(TargetType.EXAM,7L,"事项",LocalDateTime.now().plusDays(1));
        verify(mapper,never()).insert(any(NotifyPlan.class));verifyNoInteractions(producer);
    }
    @Test void savedPlanIsEnqueuedOnlyAfterCommit() {
        var captured=ArgumentCaptor.forClass(NotifyPlan.class);
        when(mapper.insert(any(NotifyPlan.class))).thenAnswer(a->{((NotifyPlan)a.getArgument(0)).setId(9L);return 1;});
        service.replaceEventReminder(TargetType.ACTIVITY,7L,"活动",LocalDateTime.now().plusDays(1));
        verify(mapper).insert(captured.capture());verifyNoInteractions(producer);
        when(mapper.selectById(9L)).thenReturn(captured.getValue());
        TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCommit());
        verify(producer).send(any(),any(),anyString());
    }
}
