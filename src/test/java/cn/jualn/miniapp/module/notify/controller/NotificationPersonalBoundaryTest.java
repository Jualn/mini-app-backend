package cn.jualn.miniapp.module.notify.controller;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.notify.bo.NotificationInboxBO;
import cn.jualn.miniapp.module.notify.converter.NotificationPersonalConverter;
import cn.jualn.miniapp.module.notify.dto.request.UpdateNotificationPreferencesRequest;
import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.service.inbox.NotificationCursorCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NotificationPersonalBoundaryTest {
    final NotificationPersonalConverter converter=new NotificationPersonalConverter();
    final ObjectMapper json=new ObjectMapper();

    @Test void explicitNullResetsButAbsentAndUnknownFieldsAreRejected() throws Exception {
        String body="{\"changes\":[{\"category\":\"ACTIVITY\",\"channel\":\"IN_APP\",\"enabled\":null}]}";
        assertNull(converter.patch(json.readValue(body,UpdateNotificationPreferencesRequest.class)).changes().get(0).enabled());
        var absent=json.readValue(body.replace(",\"enabled\":null",""),UpdateNotificationPreferencesRequest.class);
        assertThrows(ContractProblemException.class,()->converter.patch(absent));
        var unknown=json.readValue(body.replace("\"enabled\":null","\"enabled\":null,\"extra\":1"),UpdateNotificationPreferencesRequest.class);
        assertThrows(ContractProblemException.class,()->converter.patch(unknown));
        var spoof=json.readValue(body.replace("\"enabled\":null","\"enabledPresent\":true"),UpdateNotificationPreferencesRequest.class);
        assertTrue(spoof.getChanges().get(0).unknownProperties().containsKey("enabledPresent"));
        assertThrows(ContractProblemException.class,()->converter.patch(spoof));
        var internal=json.readValue(body.replace("{\"changes\":","{\"unknownProperties\":{},\"changes\":"),UpdateNotificationPreferencesRequest.class);
        assertThrows(ContractProblemException.class,()->converter.patch(internal));
    }

    @Test void legacyIdsAliasesUnknownTypesAndBeijingTimestampsKeepTheirWireShape() {
        var page=new NotificationInboxBO.Page(List.of(
            new NotificationInboxBO.Item(9007199254740993L,19,null,"title","content",new NotificationInboxBO.Target("EXAM",42L),false,LocalDateTime.of(2026,10,31,0,0)),
            new NotificationInboxBO.Item(7L,777,null,"title","content",null,true,LocalDateTime.of(2026,10,31,0,0))),null);
        var wire=json.valueToTree(converter.page(page));
        assertEquals("9007199254740993",wire.at("/items/0/notificationId").asText());
        assertEquals("COMMENTED_ME",wire.at("/items/0/type").asText());
        assertEquals("PUBLIC_EVENT",wire.at("/items/0/target/type").asText());
        assertEquals("42",wire.at("/items/0/target/resourceId").asText());
        assertEquals("2026-10-31T00:00:00+08:00",wire.at("/items/0/createdAt").asText());
        assertEquals("UNKNOWN_777",wire.at("/items/1/type").asText());
        assertFalse(wire.at("/items/1").has("category"));assertFalse(wire.at("/items/1").has("target"));
    }

    @Test void preRefactorCursorWorksAndStillRejectsOtherUsersOrFilters() {
        String token="djF8MjAyNi0xMC0zMVQxMDoyMDozMHw0Mnw2TGp1a3plX3NMY1hqbjl4S05mN25JOFZWdTRqYjhUQlpwZzZQTTkzREhr";
        var query=new NotificationInboxBO.Query(token,20,NotificationCategory.ACTIVITY,true);
        var position=NotificationCursorCodec.decode(7,query);
        assertEquals(42,position.id());assertEquals(LocalDateTime.of(2026,10,31,10,20,30),position.createdAt());
        assertEquals(token,NotificationCursorCodec.encode(7,query,position.createdAt(),position.id()));
        assertThrows(ContractProblemException.class,()->NotificationCursorCodec.decode(8,query));
        assertThrows(ContractProblemException.class,()->NotificationCursorCodec.decode(7,new NotificationInboxBO.Query(token,20,NotificationCategory.PUBLIC_EVENT,true)));
        assertThrows(ContractProblemException.class,()->NotificationCursorCodec.decode(7,new NotificationInboxBO.Query(token,20,NotificationCategory.ACTIVITY,false)));
    }
}
