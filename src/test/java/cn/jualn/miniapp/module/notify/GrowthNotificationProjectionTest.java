package cn.jualn.miniapp.module.notify;

import cn.jualn.miniapp.module.notify.service.NotificationContentAccess;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationSnapshotProjection;
import cn.jualn.miniapp.module.notify.entity.Notification;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrowthNotificationProjectionTest {
    Notification notification(){var n=new Notification();n.setId(1L);n.setUserId(2L);n.setType(1);n.setTargetType(1);n.setTargetId(55L);n.setTitle("old private title");n.setContent("old private body");return n;}
    @Test void revokedGrowthAccessRedactsHistoricalSnapshotAndPermittedTargetUsesGrowthNavigation(){
        var access=mock(NotificationContentAccess.class);var projection=new NotificationSnapshotProjection(new ObjectMapper(),new SimpleMeterRegistry(),List.of());org.springframework.test.util.ReflectionTestUtils.setField(projection,"access",List.of(access));
        when(access.evaluate(2,1,55L)).thenReturn(new NotificationContentAccess.Decision(true,false,"55"));var hidden=projection.item(notification());assertNull(hidden.presentation().body());assertNull(hidden.subject());assertNull(hidden.target());assertNull(hidden.actor());assertFalse(hidden.presentation().title().contains("old private"));
        when(access.evaluate(2,1,55L)).thenReturn(new NotificationContentAccess.Decision(true,true,"55"));var permitted=projection.item(notification());assertTrue(permitted.target().growthRecordTarget());assertEquals("55",permitted.target().postId());
    }
    @Test void unknownCurrentSourceDoesNotFallBackToFrozenPrivateText(){
        var access=mock(NotificationContentAccess.class);when(access.evaluate(2,1,55L)).thenThrow(cn.jualn.miniapp.module.post.GrowthProblems.source());var projection=new NotificationSnapshotProjection(new ObjectMapper(),new SimpleMeterRegistry(),List.of());org.springframework.test.util.ReflectionTestUtils.setField(projection,"access",List.of(access));
        assertEquals(503,assertThrows(cn.jualn.miniapp.common.exception.ContractProblemException.class,()->projection.item(notification())).getStatus().value());
    }
}
