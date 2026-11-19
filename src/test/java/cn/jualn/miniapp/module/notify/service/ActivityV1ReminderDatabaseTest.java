package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.module.notify.async.ReminderCatchUpPayload;
import cn.jualn.miniapp.module.notify.reminder.ReminderSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the existing reminder mechanism suite plus Activity V1 catch-up against the owned V84 schema. */
@EnabledIfSystemProperty(named="activity.v1.jdbcUrl",matches="jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/activity_v1_test[0-9]+")
class ActivityV1ReminderDatabaseTest extends ActivityReminderDatabaseTest {
    @Override String jdbcUrl() { return System.getProperty("activity.v1.jdbcUrl"); }
    ReminderSpec rule(int type) {
        return policy.evaluate(sql.getMapper(cn.jualn.miniapp.module.activity.mapper.ActivityMapper.class).selectReminderStatus(activityId),nodes(),now)
                .stream().filter(r->r.notificationType().getCode()==type).findFirst().orElseThrow();
    }
    void catchUp(long user, ReminderSpec rule) {
        tx.executeWithoutResult(s->notify.catchUpReminder(new ReminderCatchUpPayload(activityId,user,rule),null));
    }
    @Test void latePublicationAndSmallUserJoiningCompletedFanoutShareOccurrenceIdentity() {
        node("ACTIVITY_START",now.plusMinutes(30),false);
        subscriber(firstUser+2,1);
        reconcileAt(now,1);
        notify.broadcastPlanFanOut(plan(8));
        assertEquals(List.of(firstUser+2),received(8));
        subscriber(firstUser,1);
        var current=rule(8);
        catchUp(firstUser,current);catchUp(firstUser,current);
        reconcileAt(now,2);
        for(var p:plans.selectActiveBySource(1,activityId)) notify.broadcastPlanFanOut(p.getId());
        assertEquals(List.of(firstUser,firstUser+2),received(8));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM notification_delivery WHERE channel='IN_APP'",Integer.class));
    }
    @Test void oldPendingSubscriberScopeCannotExcludeCurrentRegistrantsDuringCatchUp() {
        node("ACTIVITY_START",now.plusMinutes(30),false);
        subscriber(firstUser+2,1);registrant(firstUser,1);
        reconcileAt(now,1);long old=plan(8);
        jdbc.update("UPDATE notify_plan SET recipient_scope='SUBSCRIBERS' WHERE id=?",old);
        reconcileAt(now,2);
        assertEquals(2,plans.selectById(old).getStatus());
        notify.broadcastPlanFanOut(plan(8));
        assertEquals(List.of(firstUser,firstUser+2),received(8));
    }
    @Test void restoredRelationshipDoesNotRepeatAndChangedAnchorGetsOneNewOccurrence() {
        node("ACTIVITY_START",now.plusMinutes(30),false);subscriber(firstUser,1);
        var original=rule(8);catchUp(firstUser,original);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM notify_plan WHERE source_id=? AND status=1 AND completed_at IS NOT NULL AND recipient_upper_user_id=?",Integer.class,activityId,firstUser));
        jdbc.update("UPDATE activity_enrollment SET status=2 WHERE activity_id=?",activityId);
        catchUp(firstUser,original);
        jdbc.update("UPDATE activity_enrollment SET status=1 WHERE activity_id=?",activityId);
        catchUp(firstUser,original);
        assertEquals(1,received(8).size());
        jdbc.update("UPDATE timeline SET start_time=? WHERE target_id=?",now.plusMinutes(45),activityId);
        catchUp(firstUser,original);
        assertEquals(1,received(8).size());
        catchUp(firstUser,rule(8));
        assertEquals(2,received(8).size());
    }
    @Test void registrationDoesNotGrantSubscriptionReminderAndTerminalOrExpiredIntentIsSuppressed() {
        node("ACTIVITY_START",now.plusMinutes(30),true);
        node("REGISTRATION_END",now.plusHours(2),false);
        registrant(firstUser,1);
        catchUp(firstUser,rule(9));assertTrue(received(9).isEmpty());
        var start=rule(8);catchUp(firstUser,start);assertEquals(1,received(8).size());
        subscriber(firstUser+1,1);
        jdbc.update("UPDATE activity SET lifecycle_status=2 WHERE id=?",activityId);
        catchUp(firstUser+1,start);assertEquals(1,received(8).size());
        jdbc.update("UPDATE activity SET lifecycle_status=0,publish_status=2 WHERE id=?",activityId);
        catchUp(firstUser+1,start);assertEquals(1,received(8).size());
        jdbc.update("UPDATE activity SET publish_status=1 WHERE id=?",activityId);
        jdbc.update("UPDATE timeline SET start_time=?,end_time=? WHERE node_type='ACTIVITY_START' AND target_id=?",now.minusHours(2),now.minusHours(1),activityId);
        assertTrue(policy.evaluate(sql.getMapper(cn.jualn.miniapp.module.activity.mapper.ActivityMapper.class).selectReminderStatus(activityId),nodes(),now).isEmpty());
        catchUp(firstUser+1,start);assertEquals(1,received(8).size());
    }
}
