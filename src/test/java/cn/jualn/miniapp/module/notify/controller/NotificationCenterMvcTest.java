package cn.jualn.miniapp.module.notify.controller;

import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.config.JacksonConfig;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import cn.jualn.miniapp.module.notify.converter.NotificationCenterConverter;
import cn.jualn.miniapp.module.notify.service.CanonicalNotificationService;
import cn.jualn.miniapp.module.notify.service.NotificationCenterService;
import cn.jualn.miniapp.module.notify.vo.CanonicalNotificationPageVO;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class NotificationCenterMvcTest {
    private final CanonicalNotificationService legacy = mock(CanonicalNotificationService.class);
    private final NotificationCenterService center = mock(NotificationCenterService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CanonicalNotificationController(legacy, center,
            Mappers.getMapper(NotificationCenterConverter.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(
                    new JacksonConfig().objectMapper(new Jackson2ObjectMapperBuilder()))).build();
    private NotificationCenterBO.Item item() {
        return new NotificationCenterBO.Item("7", "INTERACTION", "POST_COMMENTED",
                new NotificationCenterBO.Presentation("有人评论了你", "预览", null, null, null), null, null,
                new NotificationCenterBO.Target("POST_DETAIL", "42", "19", null, null), null, "2026-09-30T12:00:00+08:00");
    }
    @Test void optInStructuredPreservesNullReadAtAndOmitsUnknownOptionalValues() throws Exception {
        when(center.list(any())).thenReturn(new NotificationCenterBO.Page(List.of(item()), null, false, "opaque-head"));
        mvc.perform(get("/v1/users/me/notifications").param("representation", "structured").param("boxCategory", "INTERACTION"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.representation").value("structured"))
                .andExpect(jsonPath("$.items[0].id").value("7"))
                .andExpect(jsonPath("$.items[0].readAt").hasJsonPath())
                .andExpect(jsonPath("$.items[0].target.type").value("POST_DETAIL"))
                .andExpect(jsonPath("$.items[0].target.activityId").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.hasMore").value(false));
    }
    @Test void explicitSnapshotFieldsSurviveListAndRecoveryWhileMissingFieldsAreOmitted() throws Exception {
        var row = new NotificationCenterBO.Item("7", "INTERACTION", "COMMENT_REPLIED",
                new NotificationCenterBO.Presentation("reply", "new reply", "old context", null, null, "post title", "original comment"),
                new NotificationCenterBO.Actor("9", "actor", null), null, null, null, "2026-10-07T12:00:00+08:00");
        when(center.list(any())).thenReturn(new NotificationCenterBO.Page(List.of(row), null, false, "head"));
        when(center.get("7")).thenReturn(row);
        mvc.perform(get("/v1/users/me/notifications").param("representation", "structured"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].presentation.subjectTitle").value("post title"))
                .andExpect(jsonPath("$.items[0].presentation.quote").value("original comment"))
                .andExpect(jsonPath("$.items[0].presentation.context").value("old context"))
                .andExpect(jsonPath("$.items[0].actor.avatarUrl").doesNotExist());
        mvc.perform(get("/v1/users/me/notifications/7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.presentation.subjectTitle").value("post title"))
                .andExpect(jsonPath("$.presentation.quote").value("original comment"));
        when(center.get("7")).thenReturn(item());
        mvc.perform(get("/v1/users/me/notifications/7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.presentation.subjectTitle").doesNotExist())
                .andExpect(jsonPath("$.presentation.quote").doesNotExist()).andExpect(jsonPath("$.actor").doesNotExist());
    }

    @Test void defaultLegacyShapeRemainsUnchanged() throws Exception {
        when(legacy.list(any())).thenReturn(new CanonicalNotificationPageVO(List.of(
                new CanonicalNotificationPageVO.Item("7", "COMMENTED_ME", null, "t", "c", null, false, "2026-09-30T12:00:00+08:00")), null));
        mvc.perform(get("/v1/users/me/notifications")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].notificationId").value("7"))
                .andExpect(jsonPath("$.items[0].isRead").value(false))
                .andExpect(jsonPath("$.representation").doesNotExist());
    }
    @Test void summaryRecoveryAndReadResultsUseContractRoutesAndNumericCounts() throws Exception {
        when(center.summary(null)).thenReturn(new NotificationCenterBO.Summary(3, "head", 0, null));
        when(center.get("7")).thenReturn(item());
        when(center.batchRead(List.of("7","7"))).thenReturn(new NotificationCenterBO.ReadResult(1, 2));
        when(center.readThrough("head")).thenReturn(new NotificationCenterBO.ReadResult(2, 0));
        mvc.perform(get("/v1/users/me/notifications/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").isNumber()).andExpect(jsonPath("$.newCount").value(0))
                .andExpect(jsonPath("$.latestNewNotification").hasJsonPath());
        mvc.perform(get("/v1/users/me/notifications/7")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("7"));
        mvc.perform(post("/v1/users/me/notifications:batch-read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationIds\":[\"7\",\"7\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changedCount").value(1)).andExpect(jsonPath("$.unreadCount").isNumber());
        mvc.perform(post("/v1/users/me/notifications:mark-read-through").contentType(MediaType.APPLICATION_JSON)
                .content("{\"throughCursor\":\"head\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changedCount").value(2));
        verify(center).batchRead(List.of("7","7"));
    }
    @Test void invalidFiltersAndClosedBodiesReturnExistingProblemFormat() throws Exception {
        for (String representation : List.of("unknown", "legacy")) {
            mvc.perform(get("/v1/users/me/notifications").param("representation", representation).param("boxCategory", "ACTIVITY"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.type").value("/problems/validation-error"));
        }
        mvc.perform(get("/v1/users/me/notifications").param("representation", "structured")
                .param("boxCategory", "ACTIVITY").param("category", "ACTIVITY")).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/users/me/notifications:batch-read").contentType(MediaType.APPLICATION_JSON)
                .content("{\"notificationIds\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/users/me/notifications:mark-read-through").contentType(MediaType.APPLICATION_JSON)
                .content("{\"throughCursor\":\"head\",\"userId\":\"other\"}")).andExpect(status().isBadRequest());
    }
}
