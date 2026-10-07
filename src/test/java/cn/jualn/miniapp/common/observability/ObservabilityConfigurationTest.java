package cn.jualn.miniapp.common.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ObservabilityConfigurationTest {

    @Test
    void httpMetricsUseRouteTemplateInsteadOfRawResourceId() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        observations.observationConfig()
                .observationHandler(new DefaultMeterObservationHandler(meters));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ObservedController())
                .addFilters(new ServerHttpObservationFilter(observations))
                .build();

        mvc.perform(get("/observed/42"))
                .andExpect(status().isOk());

        Timer templated = meters.find("http.server.requests")
                .tag("uri", "/observed/{id}")
                .timer();
        assertNotNull(templated);
        assertEquals(1L, templated.count());
        assertNull(meters.find("http.server.requests").tag("uri", "/observed/42").timer());
    }

    @Test
    void productionHealthGroupsSeparateLivenessFromDependencies() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "prod", new ClassPathResource("application-prod.yaml"));
        PropertySource<?> source = sources.get(0);

        assertEquals("livenessState",
                source.getProperty("management.endpoint.health.group.liveness.include[0]"));
        assertEquals("readinessState",
                source.getProperty("management.endpoint.health.group.readiness.include[0]"));
        assertEquals("db",
                source.getProperty("management.endpoint.health.group.readiness.include[1]"));
        assertEquals("redis",
                source.getProperty("management.endpoint.health.group.readiness.include[2]"));
    }

    @Test
    void logbackConfigurationIsWellFormedAndRendersCorrelationKeys() throws Exception {
        ClassPathResource resource = new ClassPathResource("logback-spring.xml");
        String xml;
        try (InputStream input = resource.getInputStream()) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (InputStream input = resource.getInputStream()) {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
        }

        for (String key : List.of("traceId", "operationId", "messageId", "jobId", "attempt")) {
            org.junit.jupiter.api.Assertions.assertTrue(xml.contains(key + "=%X{" + key));
        }
        org.junit.jupiter.api.Assertions.assertTrue(xml.contains("<maxFileSize>${MAX_FILE_SIZE}</maxFileSize>"));
        org.junit.jupiter.api.Assertions.assertTrue(xml.contains("<totalSizeCap>${TOTAL_SIZE}</totalSizeCap>"));
        org.junit.jupiter.api.Assertions.assertEquals(1, occurrences(xml, "class=\"ch.qos.logback.core.rolling.RollingFileAppender\""));
    }

    private int occurrences(String value, String needle) {
        return (value.length() - value.replace(needle, "").length()) / needle.length();
    }

    @RestController
    static class ObservedController {
        @GetMapping("/observed/{id}")
        void getOne(@PathVariable String id) {
        }
    }
}
