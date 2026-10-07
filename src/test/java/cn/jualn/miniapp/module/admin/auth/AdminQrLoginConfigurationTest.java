package cn.jualn.miniapp.module.admin.auth;

import cn.jualn.miniapp.module.admin.auth.config.AdminQrLoginProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdminQrLoginConfigurationTest {
    private AdminQrLoginProperties load(String profile, String override) throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("base", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        if (profile != null) {
            for (var source : loader.load(profile, new ClassPathResource("application-" + profile + ".yaml"))) {
                environment.getPropertySources().addFirst(source);
            }
        }
        if (override != null) {
            environment.getPropertySources().addFirst(new MapPropertySource("override",
                    Map.of("ADMIN_QR_LOGIN_ENV_VERSION", override)));
        }
        var properties = Binder.get(environment).bind("admin-auth.qr-login-v2", AdminQrLoginProperties.class).get();
        properties.validate();
        return properties;
    }

    @Test void productionIsEnabledAndTargetsRelease() throws IOException {
        var properties = load("prod", null);
        assertTrue(properties.isEnabled());
        assertEquals("release", properties.getEnvVersion());
        assertTrue(properties.isCheckPath());
    }

    @Test void developmentIsEnabledAndTargetsDevelop() throws IOException {
        var properties = load("dev", null);
        assertTrue(properties.isEnabled());
        assertEquals("develop", properties.getEnvVersion());
        assertFalse(properties.isCheckPath());
    }

    @Test void explicitEnvironmentOverridesProfile() throws IOException {
        assertEquals("trial", load("dev", "trial").getEnvVersion());
        assertEquals("trial", load("prod", "trial").getEnvVersion());
    }

    @Test void invalidEnvironmentStillFailsAtConfigurationBoundary() {
        assertThrows(IllegalArgumentException.class, () -> load("prod", "invalid"));
    }
}
