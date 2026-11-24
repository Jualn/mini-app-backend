package cn.jualn.miniapp.module.publicevent.service;

import cn.jualn.miniapp.module.publicevent.mapper.PublicEventMapper;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventSubscriptionMapper;
import cn.jualn.miniapp.module.publicevent.mapper.PublicEventViewCountRow;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicEventCollaborationTest {
    @Test void notificationRecipientsUseTheCapturedUpperBoundInTheOwnerQuery() {
        var subscriptions = mock(PublicEventSubscriptionService.class);
        when(subscriptions.listSubscriberUserIds(8L, 10L, 20L, 100)).thenReturn(List.of(11L, 19L));
        var source = new PublicEventNotificationSource(subscriptions);
        assertEquals(List.of(11L, 19L), source.recipients(8L, "SUBSCRIBERS", 10L, 20L, 100));
        verify(subscriptions).listSubscriberUserIds(8L, 10L, 20L, 100);
        verifyNoMoreInteractions(subscriptions);
    }

    @Test void unsupportedRecipientScopeCannotQuerySubscribers() {
        var subscriptions = mock(PublicEventSubscriptionService.class);
        var source = new PublicEventNotificationSource(subscriptions);
        assertThrows(IllegalArgumentException.class, () -> source.recipients(8L, "EVERYONE", 0L, 20L, 100));
        verifyNoInteractions(subscriptions);
    }

    @Test void subscriptionPagingSqlContainsRealOperatorsAndBothCursorBounds() {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(PublicEventSubscriptionMapper.class);
        var statement = configuration.getMappedStatement(PublicEventSubscriptionMapper.class.getName() + ".selectSubscriberUserIdsBounded");
        var sql = statement.getBoundSql(Map.of("examId", 8L, "lastUserId", 10L, "upperUserId", 20L, "limit", 100));
        assertTrue(sql.getSql().contains("user_id > ?"));
        assertTrue(sql.getSql().contains("user_id <= ?"));
        assertFalse(sql.getSql().contains("&gt;"));
        assertFalse(sql.getSql().contains("&lt;"));
        assertEquals(List.of("examId", "lastUserId", "upperUserId", "limit"),
                sql.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList());
    }

    @Test void viewProjectionIsOwnedAndResolvedByPublicEvent() throws Exception {
        var configuration = new MybatisConfiguration();
        String path = "mapper/PublicEventMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        var result = configuration.getMappedStatement(PublicEventMapper.class.getName() + ".selectViewCountBatch");
        assertEquals(PublicEventViewCountRow.class, result.getResultMaps().get(0).getType());
    }
}
