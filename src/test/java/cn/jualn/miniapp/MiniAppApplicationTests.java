package cn.jualn.miniapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full application, on an explicitly selected disposable database/Redis only.
 */
@EnabledIfSystemProperty(named = "context.test.jdbcUrl", matches = "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/application_context_test")
@SpringBootTest(properties = "spring.profiles.active=test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MiniAppApplicationTests {
    @Autowired
    ApplicationContext context;

    @DynamicPropertySource
    static void disposable(DynamicPropertyRegistry properties) {
        String url = System.getProperty("context.test.jdbcUrl");
        if (url == null || !url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/application_context_test"))
            throw new IllegalStateException("Dedicated context database required");
        String options = "?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        new JdbcTemplate(new DriverManagerDataSource(url.substring(0, url.lastIndexOf('/')) + "/mysql" + options, "root", ""))
                .execute("CREATE DATABASE application_context_test");
        properties.add("spring.datasource.url", () -> url + options);
        properties.add("spring.datasource.username", () -> "root");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.data.redis.host", () -> "127.0.0.1");
        properties.add("spring.data.redis.port", () -> System.getProperty("context.test.redisPort"));
        properties.add("spring.data.redis.password", () -> "");
        properties.add("sa-token.jwt-secret-key", () -> "phase3c-synthetic-context-secret-not-production");
        properties.add("cos.secret-id", () -> "synthetic-context-id");
        properties.add("cos.secret-key", () -> "synthetic-context-key");
        properties.add("cos.bucket", () -> "synthetic-context-1250000000");
        properties.add("cos.region", () -> "ap-guangzhou");
        properties.add("cos.public-url-prefix", () -> "https://synthetic.invalid");
        properties.add("cos.custom-domain", () -> "https://synthetic.invalid");
        properties.add("cos.presign-expire-seconds", () -> 3600);
    }

    @Test
    void contextLoads() {
        assertEquals(java.time.ZoneOffset.UTC, context.getBean("learningTaskClock", java.time.Clock.class).getZone());
        assertTrue(context.containsBean("learningTaskClockConfiguration"));
        assertFalse(context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class) instanceof com.fasterxml.jackson.dataformat.xml.XmlMapper);
        assertNotNull(context.getBean("xmlMapper", com.fasterxml.jackson.dataformat.xml.XmlMapper.class));
    }

}
