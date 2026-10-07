package cn.jualn.miniapp.module.activity.controller;

import cn.jualn.miniapp.module.exam.controller.CanonicalPublicEventController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalSubscriptionRouteTest {

    @Test
    void canonicalSubscriptionsUsePutRatherThanPost() throws Exception {
        assertPutOnly(CanonicalActivityController.class.getDeclaredMethod("subscribe", Long.class));
        assertPutOnly(CanonicalPublicEventController.class.getDeclaredMethod("subscribe", Long.class));
    }

    private void assertPutOnly(Method method) {
        assertThat(method.getAnnotation(PutMapping.class)).isNotNull();
        assertThat(method.getAnnotation(PostMapping.class)).isNull();
    }
}
