package cn.jualn.miniapp.common.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import cn.jualn.miniapp.common.exception.GlobalExceptionHandler;
import cn.jualn.miniapp.common.filter.RequestLogFilter;
import cn.jualn.miniapp.config.AsyncConfig;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class HttpLoggingIntegrationTest {
    @Test
    void realHttpConcurrentSyncAsyncFailuresAndCompletionKeepOneSafeSummaryPerRequest() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger filter = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
        Level previousLevel = filter.getLevel();
        filter.setLevel(Level.DEBUG);
        List<ILoggingEvent> events = new CopyOnWriteArrayList<>();
        AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                events.add(event);
            }
        };
        capture.setContext(root.getLoggerContext());
        capture.start();
        root.addAppender(capture);
        var callers = Executors.newFixedThreadPool(8);
        try (var context = new AnnotationConfigServletWebServerApplicationContext(HttpConfig.class)) {
            int port = context.getWebServer().getPort();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            List<Callable<Void>> requests = IntStream.range(0, 48).mapToObj(i -> (Callable<Void>) () -> {
                String kind = i % 3 == 0 ? "async" : i % 3 == 1 ? "sync" : "failure";
                String trace = "http-" + i;
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/fixture/" + kind + "/" + i
                                + "?password=SYNTHETIC_SECRET"))
                        .header("X-Trace-Id", trace).header("Authorization", "Bearer SYNTHETIC_SECRET")
                        .header("Cookie", "session=SYNTHETIC_SECRET").timeout(Duration.ofSeconds(10)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(trace, response.headers().firstValue("X-Trace-Id").orElseThrow());
                assertEquals("failure".equals(kind) ? 500 : 200, response.statusCode());
                assertTrue(response.body().contains(trace));
                return null;
            }).toList();
            for (var future : callers.invokeAll(requests)) future.get(15, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (summaries(events).size() < 48 && System.nanoTime() < deadline) Thread.onSpinWait();
            List<ILoggingEvent> summaries = summaries(events);
            assertEquals(48, summaries.size());
            assertEquals(48, summaries.stream().map(e -> e.getMDCPropertyMap().get("traceId")).distinct().count());
            for (var summary : summaries) {
                String fields = summary.getKeyValuePairs().toString();
                assertTrue(fields.contains("/fixture/"));
                assertTrue(fields.contains("{id}"));
                assertFalse(fields.contains("SYNTHETIC_SECRET"));
                assertFalse(summary.getMDCPropertyMap().containsKey("Authorization"));
            }
            assertEquals(16, events.stream().filter(e -> e.getLoggerName().equals(GlobalExceptionHandler.class.getName())
                    && e.getLevel() == Level.ERROR).count());
            assertEquals(16, events.stream().filter(e -> e.getFormattedMessage().startsWith("async-work http-")).count());
            assertTrue(events.stream().filter(e -> e.getFormattedMessage().startsWith("async-work http-"))
                    .allMatch(e -> e.getFormattedMessage().equals("async-work " + e.getMDCPropertyMap().get("traceId"))));
            assertNull(MDC.get("traceId"));
        } finally {
            callers.shutdownNow();
            filter.setLevel(previousLevel);
            root.detachAppender(capture);
            capture.stop();
        }
    }

    private List<ILoggingEvent> summaries(List<ILoggingEvent> events) {
        return events.stream().filter(e -> e.getFormattedMessage().equals("HTTP request completed")).toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(AsyncConfig.class)
    static class HttpConfig {
        @Bean TomcatServletWebServerFactory server() { return new TomcatServletWebServerFactory(0); }
        @Bean DispatcherServlet dispatcher() { return new DispatcherServlet(); }
        @Bean ServletRegistrationBean<DispatcherServlet> servlet(DispatcherServlet dispatcher) {
            var registration = new ServletRegistrationBean<>(dispatcher, "/");
            registration.setAsyncSupported(true);
            return registration;
        }
        @Bean FilterRegistrationBean<RequestLogFilter> filter() {
            var registration = new FilterRegistrationBean<>(new RequestLogFilter());
            registration.setAsyncSupported(true);
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
            return registration;
        }
        @Bean GlobalExceptionHandler advice() { return new GlobalExceptionHandler(); }
        @Bean Controller controller() { return new Controller(); }
    }

    @RestController
    static class Controller {
        @GetMapping("/fixture/sync/{id}")
        String sync(@PathVariable String id) { return MDC.get("traceId"); }
        @GetMapping("/fixture/async/{id}")
        Callable<String> async(@PathVariable String id) {
            return () -> {
                String trace = MDC.get("traceId");
                LoggerFactory.getLogger(Controller.class).info("async-work {}", trace);
                return trace;
            };
        }
        @GetMapping("/fixture/failure/{id}")
        String failure(@PathVariable String id) { throw new IllegalStateException("SYNTHETIC_SECRET"); }
    }
}
