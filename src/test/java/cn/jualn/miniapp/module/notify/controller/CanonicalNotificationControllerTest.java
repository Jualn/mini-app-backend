package cn.jualn.miniapp.module.notify.controller;
import cn.jualn.miniapp.module.notify.service.inbox.PersonalNotificationInbox;
import cn.jualn.miniapp.module.notify.service.preference.NotificationPreferences;
import cn.jualn.miniapp.module.notify.service.capability.NotificationCapabilityQueries;
import cn.jualn.miniapp.module.notify.converter.NotificationPersonalConverter;
import cn.jualn.miniapp.module.notify.bo.NotificationInboxBO;
import cn.jualn.miniapp.module.notify.bo.NotificationPreferencesBO;

import cn.jualn.miniapp.module.notify.service.inbox.NotificationCenterService;

import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.notify.dto.request.UpdateNotificationPreferencesRequest;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CanonicalNotificationControllerTest {
    private final PersonalNotificationInbox service = mock(PersonalNotificationInbox.class);
    private final NotificationPreferences preferences = mock(NotificationPreferences.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CanonicalNotificationController(service,preferences,mock(NotificationCapabilityQueries.class),new NotificationPersonalConverter(),
            mock(cn.jualn.miniapp.module.notify.service.inbox.NotificationCenterService.class),
            org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.notify.converter.NotificationCenterConverter.class),org.mockito.Mockito.mock(cn.jualn.miniapp.module.notify.service.capability.NotificationCatalogQueries.class),org.mapstruct.factory.Mappers.getMapper(cn.jualn.miniapp.module.notify.converter.NotificationCatalogConverter.class)))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder())))
            .build();

    @Test
    void unreadCountRemainsANumberWithoutNarrowingLongCounts() throws Exception {
        when(service.unreadCount()).thenReturn(2147483648L);
        mvc.perform(get("/v1/users/me/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.unreadCount").isNumber())
                .andExpect(jsonPath("$.unreadCount").value(2147483648L));
    }

    @Test
    void batchUpdateUsesCanonicalUpdateAndReturnsCompleteRepresentation() throws Exception {
        when(preferences.updatePreferences(any(NotificationPreferencesBO.Patch.class)))
                .thenReturn(new NotificationPreferencesBO("1", List.of(
                        new NotificationPreferencesBO.Item(
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

        verify(preferences).updatePreferences(any(NotificationPreferencesBO.Patch.class));
    }
}
