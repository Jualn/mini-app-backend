package cn.jualn.miniapp.module.documentimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in local samples, separate from application logs and the public SSE protocol. */
@Slf4j
final class DocumentImportDiagnostics {
    private final ObjectMapper mapper;
    private final DocumentImportProperties properties;

    DocumentImportDiagnostics(ObjectMapper mapper, DocumentImportProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    Map<String, Object> begin(String importId, String filename, DocumentImportTarget target) {
        if (!properties.isDiagnosticsEnabled()) return null;
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("sampleVersion", 1);
        sample.put("importId", importId);
        sample.put("startedAt", Instant.now().toString());
        sample.put("filename", filename);
        sample.put("targetType", target.name());
        sample.put("outcome", "incomplete");
        return sample;
    }

    synchronized void write(Map<String, Object> sample) {
        if (sample == null) return;
        boolean interrupted = Thread.interrupted();
        Path temporary = null;
        try {
            Path directory = Path.of(properties.getDiagnosticsDirectory()).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            // Stop collection at 100 files; never silently delete a user's comparison samples.
            try (var files = Files.list(directory)) {
                if (files.filter(path -> path.getFileName().toString().endsWith(".json")).limit(100).count() >= 100) {
                    log.warn("Document import diagnostic sample limit reached importId={}", sample.get("importId"));
                    return;
                }
            }
            sample.put("finishedAt", Instant.now().toString());
            temporary = Files.createTempFile(directory, ".sample-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), sample);
            Path output = directory.resolve(sample.get("importId") + ".json");
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE);
            log.info("Document import diagnostic sample saved importId={} path={}", sample.get("importId"), output);
        } catch (IOException | RuntimeException exception) {
            // Diagnostic storage must not turn an otherwise usable import into a failure.
            log.warn("Document import diagnostic sample unavailable importId={} exceptionType={}",
                    sample.get("importId"), exception.getClass().getSimpleName());
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException exception) {
                    log.warn("Document import diagnostic temporary file cleanup failed exceptionType={}",
                            exception.getClass().getSimpleName());
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
