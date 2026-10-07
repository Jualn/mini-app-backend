package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentImportCancellationTest {
    @Test
    void clientCompletionCancelsInFlightGeneration() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DocumentImportFileReader reader = mock(DocumentImportFileReader.class);
        MultipartFile file = mock(MultipartFile.class);
        when(reader.read(file)).thenReturn(new DocumentImportFileReader.ParsedDocument("source", "PDF_TEXT_LAYER"));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        DocumentImportAiGateway gateway = prompt -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return "";
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                stopped.countDown();
            }
        };

        SseEmitter emitter = mock(SseEmitter.class);
        AtomicReference<Runnable> completion = new AtomicReference<>();
        doAnswer(invocation -> {
            completion.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onCompletion(org.mockito.ArgumentMatchers.any());
        DocumentImportEmitterFactory factory = mock(DocumentImportEmitterFactory.class);
        when(factory.create(anyLong())).thenReturn(emitter);
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("document-import-test-");
        DocumentImportService service = new DocumentImportService(reader, new DocumentImportPromptBuilder(), gateway,
                new DocumentImportSuggestionValidator(mapper), mapper, factory, executor,
                new DocumentImportProperties());

        service.importDocument(file, DocumentImportTarget.ACTIVITY, "r1");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        completion.get().run();
        assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    void timeoutSendsSingleFailedTerminalAndCancelsGeneration() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DocumentImportFileReader reader = mock(DocumentImportFileReader.class);
        MultipartFile file = mock(MultipartFile.class);
        when(reader.read(file)).thenReturn(new DocumentImportFileReader.ParsedDocument("source", "DOC_BODY_TEXT"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        DocumentImportAiGateway gateway = prompt -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return "";
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                stopped.countDown();
            }
        };
        CapturingEmitter emitter = new CapturingEmitter();
        DocumentImportEmitterFactory factory = mock(DocumentImportEmitterFactory.class);
        when(factory.create(anyLong())).thenReturn(emitter);
        DocumentImportService service = new DocumentImportService(reader, new DocumentImportPromptBuilder(), gateway,
                new DocumentImportSuggestionValidator(mapper), mapper, factory,
                new SimpleAsyncTaskExecutor("document-import-timeout-test-"), new DocumentImportProperties());

        service.importDocument(file, DocumentImportTarget.ACTIVITY, "r2");
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        emitter.timeout.run();
        assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(emitter.wire.toString()).contains("event:failed", "\"code\":\"TIMEOUT\"",
                "\"stage\":\"GENERATION\"", "\"baseRevision\":\"r2\"");
        assertThat(emitter.wire.toString()).doesNotContain("event:completed");
        assertThat(emitter.completed).isTrue();
    }

    private static final class CapturingEmitter extends SseEmitter {
        private final StringBuilder wire = new StringBuilder();
        private Runnable timeout;
        private boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            builder.build().forEach(item -> wire.append(item.getData()));
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeout = callback;
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}
