package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.notify.entity.*;
import cn.jualn.miniapp.module.notify.mapper.NotificationDeliveryMapper;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import cn.jualn.miniapp.module.wx.notice.WxDeliveryTemplateSnapshot;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Reuses only the disposable DB harness; no Spring application or real provider is started. */
@EnabledIfSystemProperty(named="event.test.jdbcUrl",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/activity_reminder_test[0-9]*")
class WeChatTemplateDatabaseTest {
    ActivityReminderDatabaseTest h;
    @BeforeEach void setup() throws Exception {h=new ActivityReminderDatabaseTest();h.setup();}
    @AfterEach void cleanup(){if(h!=null)h.cleanup();}
    WxDeliveryTemplateSnapshot snapshot() {
        return new WxDeliveryTemplateSnapshot(1,"WECHAT_OFFICIAL_ACCOUNT","mp-primary","test","fixture-account",
                "OFFICIAL_ACCOUNT_SUBSCRIBE_BIZSEND","fixture-binding",3,"fixture-definition",4,"fixture-template",
                Map.of("thing1","frozen-data"),"/pages/message/message?notificationId=7","fixture-jump","fixture-digest");
    }
    Notification notification() {
        return Notification.builder().userId(h.firstUser).type(NotifyType.ACTIVITY_START_REMINDER.getCode())
                .title("fixture").content("fixture").inboxGeneration("CANONICAL").isRead(0).contentSchemaVersion(1)
                .contentPayload("{\"activityId\":71001,\"activityTitle\":\"fixture\",\"startTime\":\"2027-03-01T10:30:00\"}").build();
    }
    @Test void deliveryJsonAndVersionsReloadUnchangedWhenConfigurationChanges() throws Exception {
        when(h.provider.isDeliveryAvailable(any())).thenReturn(true);
        when(h.provider.prepare(any(),any())).thenReturn(snapshot());
        var n=notification();h.tx.executeWithoutResult(t->{h.notifications.insert(n);h.delivery.planCanonicalDeliveries(n,
                NotifyType.ACTIVITY_START_REMINDER,Set.of(NotificationChannel.IN_APP,NotificationChannel.WECHAT_OFFICIAL_ACCOUNT));});
        long id=h.jdbc.queryForObject("SELECT id FROM notification_delivery WHERE channel='WECHAT_OFFICIAL_ACCOUNT'",Long.class);
        var mapper=h.sql.getMapper(NotificationDeliveryMapper.class);var saved=mapper.selectDelivery(id);
        assertEquals(1,saved.getTemplateSnapshotVersion());assertEquals(snapshot(),h.json.readValue(saved.getTemplateSnapshot(),WxDeliveryTemplateSnapshot.class));
        reset(h.provider); // Changed runtime config cannot replace the persisted provider representation.
        assertEquals(saved.getTemplateSnapshot(),mapper.selectDelivery(id).getTemplateSnapshot());
        assertEquals(1,h.jdbc.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type='notification.wechat-deliver'",Integer.class));
        assertEquals(1,h.notifications.selectCanonicalInbox(h.firstUser,null,null,null,null,10).size());
        assertEquals(1,mapper.startProviderAttempt(id,java.time.LocalDateTime.now()));
        assertEquals(1,mapper.markRetryable(id,"remote","-1","safe"));
        assertEquals(saved.getTemplateSnapshot(),mapper.selectDelivery(id).getTemplateSnapshot());
        h.delivery.execute(id); // Disabled current runtime gates safely stop this retry.
        assertEquals("SKIPPED",mapper.selectDelivery(id).getStatus());
    }
    @Test void transactionRollbackDoesNotLeaveDeliveryOrJobBehind() {
        when(h.provider.isDeliveryAvailable(any())).thenReturn(true);
        when(h.provider.prepare(any(),any())).thenReturn(snapshot());
        assertThrows(IllegalStateException.class,()->h.tx.executeWithoutResult(t->{var n=notification();h.notifications.insert(n);
            h.delivery.planCanonicalDeliveries(n,NotifyType.ACTIVITY_START_REMINDER,Set.of(NotificationChannel.IN_APP,NotificationChannel.WECHAT_OFFICIAL_ACCOUNT));
            throw new IllegalStateException("synthetic rollback");}));
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery",Integer.class));
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type='notification.wechat-deliver'",Integer.class));
        assertEquals(0,h.notifications.selectCanonicalInbox(h.firstUser,null,null,null,null,10).size());
    }
    @Test void invalidTemplateKeepsInAppCommittedAndDoesNotQueueRemoteWork() {
        when(h.provider.isDeliveryAvailable(any())).thenReturn(true);
        when(h.provider.prepare(any(),any())).thenThrow(new BusinessException(ResultCode.WX_NOTICE_PAYLOAD_INVALID,"fixture"));
        h.tx.executeWithoutResult(t->{var n=notification();h.notifications.insert(n);h.delivery.planCanonicalDeliveries(n,
                NotifyType.ACTIVITY_START_REMINDER,Set.of(NotificationChannel.IN_APP,NotificationChannel.WECHAT_OFFICIAL_ACCOUNT));});
        assertEquals(1,h.notifications.selectCanonicalInbox(h.firstUser,null,null,null,null,10).size());
        assertEquals("FAILED",h.jdbc.queryForObject("SELECT status FROM notification_delivery WHERE channel='WECHAT_OFFICIAL_ACCOUNT'",String.class));
        assertEquals(0,h.jdbc.queryForObject("SELECT COUNT(*) FROM async_job WHERE job_type='notification.wechat-deliver'",Integer.class));
    }
    @Test void deadJobsRepairPendingToFailedAndProcessingToUnknownWithoutTouchingSnapshots() {
        h.tx.executeWithoutResult(t->{
            for(String status:List.of("PENDING","PROCESSING")) {
                var n=notification();h.notifications.insert(n);
                var d=NotificationDelivery.builder().notificationId(n.getId()).channel("WECHAT_OFFICIAL_ACCOUNT").status(status)
                        .templateSnapshotVersion(1).templateSnapshot("{\"fixture\":true}").attemptCount(0).build();
                h.sql.getMapper(NotificationDeliveryMapper.class).insert(d);
                h.jobs.create(new cn.jualn.miniapp.infrastructure.async.job.JobDefinition("notification.wechat-deliver",2,"fixture-operation",
                        "delivery:"+d.getId(),"notification-delivery",d.getId().toString(),Map.of("deliveryId",d.getId()),null,4));
            }
        });
        h.jdbc.update("UPDATE async_job SET status='DEAD',dead_at=NOW(3) WHERE job_type='notification.wechat-deliver'");
        assertEquals(2,h.sql.getMapper(NotificationDeliveryMapper.class).repairDeadJobResults(10));
        assertEquals(Set.of("FAILED","UNKNOWN"),new HashSet<>(h.jdbc.queryForList("SELECT status FROM notification_delivery",String.class)));
        assertEquals(2,h.jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE JSON_EXTRACT(template_snapshot,'$.fixture')=true",Integer.class));
        verifyNoInteractions(h.provider);
    }

}
