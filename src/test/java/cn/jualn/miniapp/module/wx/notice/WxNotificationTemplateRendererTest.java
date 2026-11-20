package cn.jualn.miniapp.module.wx.notice;

import cn.jualn.miniapp.common.exception.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WxNotificationTemplateRendererTest {
    private final WxNotificationTemplateRenderer renderer = new WxNotificationTemplateRenderer();

    @Test
    void frozenJsonTimestampAndIsoStringProduceSameProviderTime() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Map<String, Object> frozen = mapper.readValue(mapper.writeValueAsString(
                Map.of("value", LocalDateTime.of(2026, 9, 29, 10, 30))), new TypeReference<>() { });
        assertEquals("2026-09-29 10:30", render(frozen, "datetime", null, true));
        assertEquals("2026-09-29 10:30", render(Map.of("value", "2026-09-29T10:30:00"), "datetime", null, true));
    }

    @Test
    void invalidTimeIsNotSentAsListOrArbitraryString() {
        assertThrows(BusinessException.class, () -> render(Map.of("value", List.of(2026, 99, 29, 10, 30)), "datetime", null, true));
        assertThrows(BusinessException.class, () -> render(Map.of("value", "not a time"), "datetime", null, true));
    }

    @Test
    void optionalTextDefaultsButRequiredTextFails() {
        assertEquals("fallback", render(Map.of(), "text", null, false));
        assertThrows(BusinessException.class, () -> render(Map.of(), "text", null, true));
    }

    @Test
    void clippingKeepsSupplementaryUnicodeCharacterWhole() {
        assertEquals("A😀", render(Map.of("value", "A😀B"), "text", 2, true));
    }

    @Test
    void pathValuesCannotInjectAnotherQueryParameter() {
        assertEquals("/pages/detail?id=A%26admin%3Dtrue", renderer.resolvePath(
                "/pages/detail?id={id}", Map.of("id", "A&admin=true")));
        assertThrows(BusinessException.class, () -> renderer.resolvePath("pages/detail?id={id}", Map.of()));
        assertNull(renderer.resolvePath(null, Map.of()));
    }

    private String render(Map<String, Object> payload, String formatter, Integer length, boolean required) {
        var field = new WxEffectiveTemplate.Field("value",formatter,"Asia/Shanghai","truncate",required?null:"fallback",required,length,List.of());
        var template = new WxEffectiveTemplate("fixture","SYSTEM","WECHAT_MINI_PROGRAM","ma-primary","MINI_PROGRAM_SUBSCRIBE","fixture",1,1,"synthetic",true,true,"fixture",Map.of("thing1",field),"INBOX",null,"formal","fixture");
        return renderer.renderData(template,payload).get("thing1");
    }
}
