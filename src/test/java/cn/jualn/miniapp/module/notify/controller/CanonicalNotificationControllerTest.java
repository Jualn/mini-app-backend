package cn.jualn.miniapp.module.notify.controller;

import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.notify.dto.request.UpdateNotificationPreferencesRequest;
import cn.jualn.miniapp.module.notify.service.CanonicalNotificationService;
import cn.jualn.miniapp.module.notify.vo.NotificationPreferencesVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CanonicalNotificationControllerTest {
    private final CanonicalNotificationService service = mock(CanonicalNotificationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CanonicalNotificationController(service,
            mock(cn.jualn.miniapp.module.notify.service.NotificationCenterService.class),
            org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.notify.converter.NotificationCenterConverter.class)))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder())))
            .build();

    @Test
    void batchUpdateUsesCanonicalUpdateAndReturnsCompleteRepresentation() throws Exception {
        when(service.updatePreferences(any(UpdateNotificationPreferencesRequest.class)))
                .thenReturn(new NotificationPreferencesVO("1", List.of(
                        new NotificationPreferencesVO.Item(
                                "ACTIVITY", "IN_APP", false, "USER_OVERRIDE"))));

        mvc.perform(post("/v1/users/me/notification-preferences:batch-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"changes":[{"category":"ACTIVITY","channel":"IN_APP","enabled":false}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.defaultVersion").value("1"))
                .andExpect(jsonPath("$.items[0].enabled").value(false));

        verify(service).updatePreferences(any(UpdateNotificationPreferencesRequest.class));
    }
}
