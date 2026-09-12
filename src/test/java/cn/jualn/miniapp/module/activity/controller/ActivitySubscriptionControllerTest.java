package cn.jualn.miniapp.module.activity.controller;

import cn.jualn.miniapp.module.activity.converter.ActivityConverter;
import cn.jualn.miniapp.module.activity.service.ActivityAiService;
import cn.jualn.miniapp.module.activity.service.ActivityEnrollmentService;
import cn.jualn.miniapp.module.activity.service.ActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP mapping tests; application authentication is covered by the real interceptor, not this standalone fixture. */
class ActivitySubscriptionControllerTest {
    private final ActivityEnrollmentService subscriptions = mock(ActivityEnrollmentService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ActivityController(
                mock(ActivityService.class), mock(ActivityAiService.class),
                mock(ActivityConverter.class), subscriptions)).build();
    }

    @Test
    void subscribePreservesLargeIdAndReturnsSuccessWithoutBody() throws Exception {
        mvc.perform(post("/v1/activity/9223372036854775800/subscribe").accept("application/json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(subscriptions).enrollActivity(9223372036854775800L);
        verifyNoMoreInteractions(subscriptions);
    }

    @Test
    void unsubscribeUsesCancellationService() throws Exception {
        mvc.perform(delete("/v1/activity/9223372036854775800/subscribe").accept("application/json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(subscriptions).unEnrollActivity(9223372036854775800L);
        verifyNoMoreInteractions(subscriptions);
    }

    @Test
    void malformedIdCannotReachSubscriptionService() throws Exception {
        mvc.perform(post("/v1/activity/not-an-id/subscribe")).andExpect(status().isBadRequest());
        verifyNoInteractions(subscriptions);
    }
}
