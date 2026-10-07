package cn.jualn.miniapp.module.activity.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityFormAvailabilityContextTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsToDisabledWhenPropertyIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ActivityFormAvailability.class).isEnabled()).isFalse();
        });
    }

    @Test
    void readsEnabledProperty() {
        contextRunner
                .withPropertyValues("activity.registration.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ActivityFormAvailability.class).isEnabled()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ActivityFormAvailability.class)
    static class TestConfiguration {
    }
}
