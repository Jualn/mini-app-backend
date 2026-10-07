package cn.jualn.miniapp.module.documentimport;

import cn.jualn.miniapp.module.documentimport.DocumentImportSuggestionValidator.Candidate;
import cn.jualn.miniapp.module.documentimport.DocumentImportSuggestionValidator.InvalidModelOutputException;
import cn.jualn.miniapp.module.documentimport.DocumentImportSuggestionValidator.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class DocumentImportService {
    private final DocumentImportFileReader fileReader;
    private final DocumentImportPromptBuilder promptBuilder;
    private final DocumentImportAiGateway aiGateway;
    private final DocumentImportSuggestionValidator suggestionValidator;
    private final ObjectMapper objectMapper;
    private final DocumentImportEmitterFactory emitterFactory;
    private final AsyncTaskExecutor executor;
    private final long streamTimeoutMillis;

    public DocumentImportService(DocumentImportFileReader fileReader,
                                 DocumentImportPromptBuilder promptBuilder,
                                 DocumentImportAiGateway aiGateway,
                                 DocumentImportSuggestionValidator suggestionValidator,
                                 @Qualifier("objectMapper") ObjectMapper objectMapper,
                                 DocumentImportEmitterFactory emitterFactory,
                                 @Qualifier("aiTaskExecutor") AsyncTaskExecutor executor,
                                 DocumentImportProperties properties) {
        this.fileReader = fileReader;
        this.promptBuilder = promptBuilder;
        this.aiGateway = aiGateway;
        this.suggestionValidator = suggestionValidator;
        this.objectMapper = objectMapper;
        this.emitterFactory = emitterFactory;
        this.executor = executor;
        this.streamTimeoutMillis = properties.getStreamTimeout().toMillis();
    }

    public SseEmitter importDocument(MultipartFile file, DocumentImportTarget target, String baseRevision) {
        DocumentImportFileReader.ParsedDocument document = fileReader.read(file);
        Session session = new Session(UUID.randomUUID().toString(), target, baseRevision,
                emitterFactory.create(streamTimeoutMillis));

        session.emitter.onTimeout(() -> {
            session.failed("TIMEOUT", "GENERATION", true, "建议生成超时，可重新上传开始新尝试。");
            session.cancel();
        });
        session.emitter.onCompletion(session::cancel);
        session.emitter.onError(error -> session.cancel());
        Future<?> future = executor.submit(() -> run(session, document));
        session.future.set(future);
        if (session.cancelled.get()) future.cancel(true);
        return session.emitter;
    }

    private void run(Session session, DocumentImportFileReader.ParsedDocument document) {
        try {
            session.send("started", Map.of("extractionScope", document.extractionScope()));
            session.warning("EXTRACTION_SCOPE_LIMITED", null,
                    "仅处理正文可提取文字，图片和其他结构中的信息需人工核对。");
            if (session.stopped()) return;

            String raw = aiGateway.generate(promptBuilder.build(
                    session.target, document.extractionScope(), document.text()));
            if (session.stopped()) return;
            ValidationResult result = suggestionValidator.validate(raw, session.target, document.text(), session.importId);
            int suggestionIndex = 0;
            for (Candidate candidate : result.suggestions()) {
                if (session.stopped()) return;
                Map<String, Object> source = Map.of("excerpts", candidate.excerpts());
                Map<String, Object> suggestion = new LinkedHashMap<>();
                suggestion.put("target", candidate.target());
                suggestion.put("value", candidate.value());
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("suggestionId", "s" + (++suggestionIndex));
                extra.put("source", source);
                extra.put("suggestion", suggestion);
                session.sendSuggestion(extra);
            }
            for (var warning : result.warnings()) {
                if (session.stopped()) return;
                session.warning(warning.code(), warning.target(), warning.message());
            }
            session.completed();
        } catch (InvalidModelOutputException exception) {
            session.failed("AI_OUTPUT_INVALID", "GENERATION", true,
                    "建议结果无法安全解析，可重新上传开始新尝试。");
        } catch (DataBufferLimitException exception) {
            session.failed("PROCESSING_LIMIT_EXCEEDED", "GENERATION", false,
                    "处理规模超出当前服务能力，请改用更小的文档。");
        } catch (WebClientException exception) {
            session.failed("AI_UNAVAILABLE", "GENERATION", true,
                    "建议服务暂时不可用，可重新上传开始新尝试。");
        } catch (RuntimeException exception) {
            if (!session.stopped()) {
                log.error("Document import failed importId={}", session.importId, exception);
                session.failed("INTERNAL_ERROR", "GENERATION", false,
                        "建议处理失败，请稍后重新上传。");
            }
        }
    }

    private final class Session {
        private final String importId;
        private final DocumentImportTarget target;
        private final String baseRevision;
        private final SseEmitter emitter;
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger suggestionCount = new AtomicInteger();
        private final AtomicInteger warningCount = new AtomicInteger();
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Future<?>> future = new AtomicReference<>();

        private Session(String importId, DocumentImportTarget target, String baseRevision, SseEmitter emitter) {
            this.importId = importId;
            this.target = target;
            this.baseRevision = baseRevision;
            this.emitter = emitter;
        }

        private boolean stopped() {
            return terminal.get() || cancelled.get() || Thread.currentThread().isInterrupted();
        }

        private void sendSuggestion(Map<String, Object> extra) {
            send("suggestion", extra);
            suggestionCount.incrementAndGet();
        }

        private void warning(String code, String targetPointer, String message) {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("code", code);
            if (targetPointer != null) extra.put("target", targetPointer);
            extra.put("message", message);
            send("warning", extra);
            warningCount.incrementAndGet();
        }

        private void completed() {
            if (!terminal.compareAndSet(false, true)) return;
            Map<String, Object> extra = Map.of(
                    "suggestionCount", suggestionCount.get(),
                    "warningCount", warningCount.get());
            sendTerminal("completed", extra);
        }

        private void failed(String code, String stage, boolean retryable, String message) {
            if (!terminal.compareAndSet(false, true)) return;
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("code", code);
            extra.put("stage", stage);
            extra.put("retryable", retryable);
            extra.put("message", message);
            sendTerminal("failed", extra);
        }

        private void send(String type, Map<String, Object> extra) {
            if (stopped()) return;
            int next = sequence.incrementAndGet();
            emit(type, next, extra, false);
        }

        private void sendTerminal(String type, Map<String, Object> extra) {
            int next = sequence.incrementAndGet();
            emit(type, next, extra, true);
        }

        private synchronized void emit(String type, int eventSequence, Map<String, Object> extra, boolean finish) {
            if (cancelled.get()) return;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("importId", importId);
            event.put("targetType", target.name());
            event.put("baseRevision", baseRevision);
            event.put("sequence", eventSequence);
            event.put("type", type);
            event.putAll(extra);
            try {
                emitter.send(SseEmitter.event()
                        .id(importId + ":" + eventSequence)
                        .name(type)
                        .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
                if (finish) emitter.complete();
            } catch (IOException exception) {
                cancelled.set(true);
                Future<?> task = future.get();
                if (task != null) task.cancel(true);
            }
        }

        private void cancel() {
            cancelled.set(true);
            Future<?> task = future.get();
            if (task != null && !task.isDone()) task.cancel(true);
        }
    }
}
