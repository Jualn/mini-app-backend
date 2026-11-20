package cn.jualn.miniapp.module.points;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.infrastructure.async.job.JobDefinition;
import cn.jualn.miniapp.infrastructure.async.job.JobService;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.notify.service.NotifyService;
import cn.jualn.miniapp.module.points.bo.PointsBO.*;
import cn.jualn.miniapp.module.points.service.PointsResultNotifications;
import cn.jualn.miniapp.module.user.service.UserCurrentLoginAccess;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PointsResultNotificationsTest {
    @Test void terminalResultCreatesDurableIntentWithoutAnEnablePropertyAndStorageFailureStillBlocks() {
        var jobs = mock(JobService.class);
        var service = new PointsResultNotifications(jobs, mock(NotifyService.class),
                mock(TransactionalAuthorization.class), mock(UserCurrentLoginAccess.class));
        var app = new Application(101, 7, "title", "description", List.of(), Status.REJECTED,
                LocalDateTime.now(), 9L, LocalDateTime.now(), "note", null, null);
        var persons=mock(cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.class);
        when(persons.deliveryBinding(any())).thenReturn(new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.DeliveryBinding("ACTIVE",17L,"current-binding"));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"persons",persons);
        service.accept(app, null);
        var intent = ArgumentCaptor.forClass(JobDefinition.class);
        verify(jobs).create(intent.capture());
        assertEquals("points.result-notification", intent.getValue().jobType());
        assertEquals("application:101:REJECTED", intent.getValue().dedupeKey());
        assertEquals(new PointsResultNotifications.Result(101, 17L, "POINT_APPLICATION_REJECTED",
                "application:101:REJECTED",null,"current-binding","BOUND",app.reviewedAt()), intent.getValue().payload());
        doThrow(new DataAccessResourceFailureException("synthetic failure")).when(jobs).create(any());
        assertEquals("NOTIFICATION_ACCEPTANCE",
                assertThrows(ContractProblemException.class, () -> service.accept(app, null)).getMessage());
    }
    @Test void reviewTemplateKeepsTheOriginalDecisionTimeAndNeverFreezesPrivateNotes() {
        var notices=mock(NotifyService.class);
        var service=new PointsResultNotifications(mock(JobService.class),notices,mock(TransactionalAuthorization.class),mock(UserCurrentLoginAccess.class));
        var persons=mock(cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.class);
        var points=mock(cn.jualn.miniapp.module.points.mapper.PointsMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"persons",persons);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"points",points);
        when(persons.deliveryBinding("person")).thenReturn(new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.DeliveryBinding("ACTIVE",17L,"binding"));
        var time=LocalDateTime.of(2026,11,20,18,30);
        var result=new PointsResultNotifications.Result(101,17L,"POINT_APPLICATION_REJECTED","application:101:REJECTED","person","binding","BOUND",time);
        service.deliver(result,mock(cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext.class));
        var capture=ArgumentCaptor.forClass(cn.jualn.miniapp.module.notify.payload.NotifyPayload.class);
        verify(notices).processNotificationPayload(capture.capture());
        var frozen=capture.getValue().getContentData().toMap();
        assertEquals("未通过",frozen.get("auditResult"));
        assertEquals(LocalDateTime.of(2026,11,21,2,30),frozen.get("auditTime"));
        assertEquals("",frozen.get("auditReason"));
    }

    @Test void bindingChangesSkipWithEvidenceAndUncertainFactsRetry() {
        var jobs=mock(JobService.class);var inbox=mock(NotifyService.class);
        var service=new PointsResultNotifications(jobs,inbox,mock(TransactionalAuthorization.class),mock(UserCurrentLoginAccess.class));
        var persons=mock(cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.class);
        var points=mock(cn.jualn.miniapp.module.points.mapper.PointsMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"persons",persons);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"points",points);
        var context=mock(cn.jualn.miniapp.infrastructure.async.job.JobExecutionContext.class);
        var result=new PointsResultNotifications.Result(101,17L,"POINT_GRANT_CORRECTED","correction:c1","person","old","BOUND");
        when(persons.deliveryBinding("person")).thenReturn(new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.DeliveryBinding("ACTIVE",18L,"new"));
        service.deliver(result,context);
        verify(points).notificationResolution(eq("correction:c1"),eq("BINDING_CHANGED"),any());verifyNoInteractions(inbox);
        when(persons.deliveryBinding("person")).thenReturn(new cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.DeliveryBinding("VERIFICATION_REQUIRED",null,null));
        assertThrows(IllegalStateException.class,()->service.deliver(result,context));
        var unbound=new PointsResultNotifications.Result(101,null,"POINT_GRANT_CORRECTED","correction:c2","person",null,"NO_BINDING");
        service.deliver(unbound,context);verify(points).notificationResolution(eq("correction:c2"),eq("NO_BINDING"),any());
    }
}
