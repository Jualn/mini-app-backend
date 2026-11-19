package cn.jualn.miniapp.common.observability;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class LoggingFoundationTest {
    private final LogbackLoggingSystem system = new LogbackLoggingSystem(getClass().getClassLoader());
    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private final PrintStream original = System.out;
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path directory;

    @AfterEach
    void restoreLogging() throws Exception {
        context.stop();
        System.setOut(original);
        MDC.clear();
        for (String key : List.of("LOG_DIR", "LOG_MAX_FILE_SIZE", "LOG_TOTAL_SIZE", "LOG_MAX_HISTORY")) {
            System.clearProperty(key);
        }
        initialize("dev");
    }

    private void initialize(String... profiles) throws Exception {
        initialize(java.util.Map.of(), profiles);
    }

    private void initialize(java.util.Map<String, String> overrides, String... profiles) throws Exception {
        system.cleanUp();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        overrides.forEach(environment::setProperty);
        for (String profile : profiles) {
            var resource = new ClassPathResource("application-" + profile + ".yaml");
            if (resource.exists()) {
                for (var source : new YamlPropertySourceLoader().load(profile, resource)) {
                    environment.getPropertySources().addLast(source);
                }
            }
        }
        for (var source : new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }
        system.beforeInitialize();
        // Exercise Boot's real level/group binding after Logback initialization, without business beans.
        new LoggingApplicationListener().onApplicationEvent(new ApplicationEnvironmentPreparedEvent(
                new DefaultBootstrapContext(), new SpringApplication(LoggingFoundationTest.class), new String[0], environment));
    }

    @Test
    void environmentLevelsKeepLifecycleAndFailuresWithoutRoutineDebugNoise() throws Exception {
        for (String profile : List.of("dev", "prod", "default")) {
            initialize(profile);
            assertEquals("prod".equals(profile) ? ch.qos.logback.classic.Level.WARN : ch.qos.logback.classic.Level.INFO,
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getEffectiveLevel());
            for (String name : List.of("cn.jualn.miniapp.module.post.service", "org.springframework.boot.web.server",
                    "com.zaxxer.hikari.HikariDataSource")) {
                assertTrue(context.getLogger(name).isInfoEnabled(), name);
                assertFalse(context.getLogger(name).isDebugEnabled(), name);
            }
            for (String name : List.of("org.springframework.web.servlet.DispatcherServlet", "org.apache.pdfbox",
                    "org.apache.poi", "com.baomidou", "org.mybatis", "cn.dev33.satoken", "io.lettuce.core",
                    "io.netty", "reactor.netty.http.client", "com.qcloud.cos", "com.tencent.cloud")) {
                assertFalse(context.getLogger(name).isInfoEnabled(), name);
                assertTrue(context.getLogger(name).isWarnEnabled(), name);
                assertTrue(context.getLogger(name).isErrorEnabled(), name);
            }
            assertEquals("dev".equals(profile), context.getLogger("org.apache.catalina.core.StandardService").isInfoEnabled());
            assertFalse(context.getLogger("cn.jualn.miniapp.common.filter.RequestLogFilter").isDebugEnabled());
            assertFalse(context.getLogger("cn.jualn.miniapp.config.WebClientConfig").isDebugEnabled());
            assertFalse(context.getLogger("org.apache.http.wire").isErrorEnabled());
            assertFalse(context.getLogger("org.apache.http.headers").isErrorEnabled());
        }
    }

    @Test
    void nativeDiagnosticGroupOnlyEnablesReviewedRequestSummary() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        initialize(java.util.Map.of("logging.level.requestlog", "DEBUG"), "dev");
        assertTrue(context.getLogger("cn.jualn.miniapp.common.filter.RequestLogFilter").isDebugEnabled());
        assertFalse(context.getLogger("cn.jualn.miniapp.module.post.service").isDebugEnabled());
        assertFalse(context.getLogger("cn.jualn.miniapp.config.WebClientConfig").isDebugEnabled());
        assertFalse(context.getLogger("org.springframework.web").isDebugEnabled());
        assertFalse(context.getLogger("org.apache.http.wire").isDebugEnabled());
        context.getLogger("cn.jualn.miniapp.common.filter.RequestLogFilter").debug("request-summary-fixture");
        context.getLogger("cn.jualn.miniapp.module.post.service").debug("must-not-output");
        context.getLogger("org.apache.http.headers").error("must-not-output");
        context.stop();
        assertEquals(1, output.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.contains("request-summary-fixture")).count());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("must-not-output"));

        output.reset();
        initialize(java.util.Map.of("logging.level.providerlog", "DEBUG"), "prod");
        assertFalse(context.getLogger("cn.jualn.miniapp.common.filter.RequestLogFilter").isDebugEnabled());
        context.getLogger("cn.jualn.miniapp.config.WebClientConfig").debug("provider-summary-fixture");
        context.getLogger("reactor.netty.http.client").debug("must-not-output");
        context.getLogger("io.lettuce.core").warn("dependency-warning-fixture");
        context.getLogger("io.lettuce.core").error("dependency-error-fixture");
        context.stop();
        var lines = output.toString(StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("{")).toList();
        assertEquals(3, lines.size());
        assertEquals("DEBUG", json.readTree(lines.get(0)).path("level").asText());
        assertTrue(lines.get(0).contains("provider-summary-fixture"));
        assertEquals("WARN", json.readTree(lines.get(1)).path("level").asText());
        assertEquals("ERROR", json.readTree(lines.get(2)).path("level").asText());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains("must-not-output"));
    }

    @Test
    void nativeProductionJsonIsSingleLineBoundedAndDoesNotExportArbitraryContextOrExceptionMessages() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        initialize("prod");
        Logger logger = context.getLogger("cn.jualn.miniapp.logging.fixture");
        MDC.put("traceId", "trace-json-1");
        MDC.put("Authorization", "Bearer SYNTHETIC_SECRET");
        MDC.put("password", "SYNTHETIC_SECRET");
        IllegalStateException failure = new IllegalStateException("SQL SELECT SYNTHETIC_SECRET",
                new IllegalArgumentException("openid=SYNTHETIC_SECRET\nforged"));
        failure.addSuppressed(new RuntimeException("Authorization: SYNTHETIC_SECRET"));
        logger.atError().addKeyValue("password", "SYNTHETIC_SECRET")
                .addKeyValue("status", 500).setCause(failure).log("failure\nforged");
        logger.info("{}", "x".repeat(9000));
        context.stop(); // real AsyncAppender flush and native encoder
        List<String> lines = output.toString(StandardCharsets.UTF_8).lines().filter(s -> s.startsWith("{")).toList();
        assertEquals(2, lines.size(), output.toString(StandardCharsets.UTF_8));
        JsonNode event = json.readTree(lines.get(0));
        assertEquals("trace-json-1", event.path("traceId").asText());
        assertEquals("ERROR", event.path("level").asText());
        assertEquals(500, event.path("status").asInt());
        assertEquals("failure_forged", event.path("message").asText());
        assertTrue(event.path("@timestamp").asText().endsWith("+08:00"));
        assertTrue(event.path("stack_trace").asText().contains("Caused by: java.lang.IllegalArgumentException"), event.toString());
        assertTrue(event.path("stack_trace").asText().contains("Suppressed: java.lang.RuntimeException"));
        assertTrue(event.path("stack_trace").asText().contains("LoggingFoundationTest"));
        assertFalse(lines.get(0).contains("SYNTHETIC_SECRET"));
        assertFalse(event.has("Authorization"));
        assertFalse(event.has("password"));
        assertEquals(2048, json.readTree(lines.get(1)).path("message").asText().length());
        assertNull(context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("OUTPUT"));
    }

    @Test
    void consoleColorsKeepContentAndOnlyOmitAbsentCorrelationFields() throws Exception {
        var previousAnsi = org.springframework.boot.ansi.AnsiOutput.getEnabled();
        try {
            for (boolean colors : List.of(false, true)) {
                org.springframework.boot.ansi.AnsiOutput.setEnabled(colors
                        ? org.springframework.boot.ansi.AnsiOutput.Enabled.ALWAYS
                        : org.springframework.boot.ansi.AnsiOutput.Enabled.NEVER);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
                initialize("dev");
                MDC.put("traceId", "trace-beauty");
                MDC.put("operationId", "operation-beauty");
                MDC.put("messageId", "message-beauty");
                MDC.put("jobId", "42");
                MDC.put("attempt", "2");
                context.getLogger("cn.jualn.miniapp.logging.console").atWarn()
                        .addKeyValue("status", 503).log("beauty-fixture");
                MDC.clear();
                context.getLogger("cn.jualn.miniapp.logging.console").info("plain-fixture");
                context.getLogger("cn.jualn.miniapp.logging.console").error("error-fixture");
                context.stop();
                String raw = output.toString(StandardCharsets.UTF_8);
                assertEquals(colors, raw.contains("\u001b["));
                if (colors) {
                    assertTrue(raw.contains("\u001b[93mWARN"));
                    assertTrue(raw.contains("\u001b[94mINFO"));
                    assertTrue(raw.contains("\u001b[91mERROR"));
                }
                var lines = raw.replaceAll("\u001b\\[[;\\d]*m", "").lines().toList();
                assertEquals(3, lines.size());
                for (String field : List.of("traceId=trace-beauty", "operationId=operation-beauty",
                        "messageId=message-beauty", "jobId=42", "attempt=2", "status=\"503\"", "beauty-fixture")) {
                    assertTrue(lines.get(0).contains(field), lines.get(0));
                }
                assertTrue(lines.get(0).contains("+08:00"));
                assertTrue(lines.get(0).contains("WARN"));
                assertTrue(lines.get(0).contains("logging.console"));
                assertFalse(lines.get(1).matches(".*(traceId|operationId|messageId|jobId|attempt)=.*"));
                assertTrue(lines.get(1).contains("plain-fixture"));
            }
            org.springframework.boot.ansi.AnsiOutput.setEnabled(org.springframework.boot.ansi.AnsiOutput.Enabled.ALWAYS);
            ByteArrayOutputStream production = new ByteArrayOutputStream();
            System.setOut(new PrintStream(production, true, StandardCharsets.UTF_8));
            initialize("prod");
            context.getLogger("cn.jualn.miniapp.logging.console").info("production-fixture");
            context.stop();
            String raw = production.toString(StandardCharsets.UTF_8);
            assertFalse(raw.contains("\u001b["));
            assertEquals("production-fixture", json.readTree(raw).path("message").asText());
        } finally {
            org.springframework.boot.ansi.AnsiOutput.setEnabled(previousAnsi);
        }
    }

    @Test
    void developmentAndDefaultOutputAreReadableAndDoNotRenderExceptionMessagesOrArbitraryMdc() throws Exception {
        for (String profile : List.of("dev", "default")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            initialize(profile);
            MDC.put("traceId", "dev-trace");
            MDC.put("password", "SYNTHETIC_SECRET");
            context.getLogger("cn.jualn.miniapp.logging.dev").error("failed\nforged",
                    new IllegalArgumentException("SYNTHETIC_SECRET"));
            context.stop();
            String text = output.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("traceId=dev-trace"));
            assertTrue(text.contains("failed_forged"));
            assertTrue(text.contains("java.lang.IllegalArgumentException [message omitted]"));
            assertFalse(text.contains("SYNTHETIC_SECRET"));
        }
    }

    @Test
    void exceptionStormCannotBlockBusinessThreadWhenTheOutputIsStalled() throws Exception {
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        initialize("prod");
        AsyncAppender async = (AsyncAppender) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC");
        assertTrue(async.isNeverBlock());
        assertEquals(512, async.getQueueSize());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ConsoleAppender<ILoggingEvent> output = (ConsoleAppender<ILoggingEvent>) async.getAppender("OUTPUT");
        output.setOutputStream(new OutputStream() {
            @Override public void write(int value) {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            @Override public void write(byte[] bytes, int offset, int length) { write(0); }
        });
        Logger logger = context.getLogger("cn.jualn.miniapp.logging.pressure");
        try {
            logger.error("first", new IllegalStateException("synthetic"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                for (int i = 0; i < 20000; i++) logger.error("failure", new IllegalStateException("synthetic"));
            });
            assertEquals(512, async.getNumberOfElementsInQueue());
        } finally {
            release.countDown();
        }
    }

    @Test
    void optionalFileModeActuallyRollsCompressesCleansOldHistoryAndCapsArchives() throws Exception {
        System.setProperty("LOG_DIR", directory.toString());
        System.setProperty("LOG_MAX_FILE_SIZE", "4KB");
        System.setProperty("LOG_TOTAL_SIZE", "24KB");
        System.setProperty("LOG_MAX_HISTORY", "2");
        Path expired = directory.resolve("mini-app." + java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                .minusDays(4) + ".0.log.gz");
        Files.writeString(expired, "expired");
        initialize("prod", "log-file");
        AsyncAppender async = (AsyncAppender) context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC");
        RollingFileAppender<?> file = (RollingFileAppender<?>) async.getAppender("OUTPUT");
        Logger logger = context.getLogger("cn.jualn.miniapp.logging.rotation");
        // Drain every small batch: this verifies retention, without queue drops confusing the result.
        for (int batch = 0; batch < 30; batch++) {
            for (int i = 0; i < 20; i++) logger.warn("rotation {} {}", batch, UUID.randomUUID().toString().repeat(20));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (async.getNumberOfElementsInQueue() > 0 && System.nanoTime() < deadline) Thread.onSpinWait();
        }
        logger.warn("rotation-finished");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean written = Files.readString(directory.resolve("mini-app.log")).contains("rotation-finished");
            var pool = (java.util.concurrent.ThreadPoolExecutor) context.getExecutorService();
            var alternate = (java.util.concurrent.ThreadPoolExecutor) context.getAlternateExecutorService();
            if (written && pool.getActiveCount() == 0 && pool.getQueue().isEmpty()
                    && alternate.getActiveCount() == 0 && alternate.getQueue().isEmpty()) break;
            Thread.sleep(10);
        }
        context.stop();
        // Compression and size-cap cleanup run independently. A subsequent real startup must
        // converge already-compressed archives to the cap, without further log writes.
        initialize("prod", "log-file");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var pool = (java.util.concurrent.ThreadPoolExecutor) context.getExecutorService();
            var alternate = (java.util.concurrent.ThreadPoolExecutor) context.getAlternateExecutorService();
            if (pool.getActiveCount() == 0 && pool.getQueue().isEmpty()
                    && alternate.getActiveCount() == 0 && alternate.getQueue().isEmpty()) break;
            Thread.sleep(10);
        }
        context.stop();
        assertFalse(Files.exists(expired));
        List<Path> archives;
        try (var files = Files.list(directory)) {
            archives = files.filter(p -> p.toString().endsWith(".gz")).toList();
        }
        assertFalse(archives.isEmpty());
        long bytes = 0;
        for (Path archive : archives) {
            bytes += Files.size(archive);
            try (var input = new GZIPInputStream(Files.newInputStream(archive))) {
                String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                for (String line : content.lines().toList()) assertNotNull(json.readTree(line));
            }
        }
        assertTrue(bytes <= 24 * 1024, "archive bytes=" + bytes);
        assertTrue(Files.size(directory.resolve("mini-app.log")) < 8 * 1024);
        assertNotNull(file);
    }
}
