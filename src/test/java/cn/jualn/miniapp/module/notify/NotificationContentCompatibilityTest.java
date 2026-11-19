package cn.jualn.miniapp.module.notify;

import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.notify.payload.NotifyPayload;
import cn.jualn.miniapp.module.notify.payload.data.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import static org.junit.jupiter.api.Assertions.*;

class NotificationContentCompatibilityTest {
    final ObjectMapper json=new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder());

    @Test void storedJavaTypeIdsRemainReadableAndNewContentUsesStableIds() throws Exception {
        var time=LocalDateTime.of(2026,10,31,10,20,30);
        for(var data:List.<NoticeData>of(new CommentNoticeData(1L,2L,"post","comment","author",time),
                new ReplyNoticeData("original","reply","author",time),
                new AuditResultNoticeData("content","approved","reason",time),
                new ActivityRemindNoticeData(3L,"activity",time,"location"),new BroadcastNoticeData("title","body","scene"))) {
            var payload=NotifyPayload.builder().contentData(data).build();
            var stored=json.valueToTree(payload);
            String id=stored.path("wxData").path("@class").asText();
            assertFalse(id.isBlank());assertFalse(id.contains("."));
            assertFalse(stored.has("contentData"),"Keep the existing durable property name");
            var roundTrip=json.treeToValue(stored,NotifyPayload.class);
            assertEquals(data.toMap(),roundTrip.getContentData().toMap());
            ((com.fasterxml.jackson.databind.node.ObjectNode)stored.path("wxData")).put("@class",
                    "cn.jualn.miniapp.module.wx.notice.data."+data.getClass().getSimpleName());
            var legacy=json.treeToValue(stored,NotifyPayload.class);
            assertEquals(data.toMap(),legacy.getContentData().toMap());
        }
    }

    @Test void unknownStoredClassCannotBeInstantiatedAsNotificationContent() {
        assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,()->json.readValue(
                "{\"wxData\":{\"@class\":\"java.lang.Runtime\"}}",NotifyPayload.class));
    }
}
