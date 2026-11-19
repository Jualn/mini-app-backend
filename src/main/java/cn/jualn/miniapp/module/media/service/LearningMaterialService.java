package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.article.service.ArticleCursor;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO.*;
import cn.jualn.miniapp.module.media.entity.LearningMaterialRow;
import cn.jualn.miniapp.module.media.mapper.LearningMaterialMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import org.springframework.http.HttpStatus;
import cn.jualn.miniapp.common.exception.ContractProblemException;

/**
 * Media owns immutable bytes and cleanup; LearningTask supplies fresh authorization in each short local transaction.
 */
@Service
public class LearningMaterialService {
    private final LearningMaterialMapper files;
    private final LearningMaterialStorage storage;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ConcurrentHashMap<String, Credentials> credentials = new ConcurrentHashMap<>();
    private final Set<String> signing = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelay = 1000)
    public void discardExpiredCredentials() {
        credentials.entrySet().removeIf(e -> !e.getValue().uploadExpiresAt().isAfter(utcNow()));
    }

    @Scheduled(fixedDelay = 1000)
    public void clearCredentials() {
        credentials.clear();
        signing.clear();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record File(String fileId, String assignmentId, String fileName, String kind, String contentType,
                       int sizeBytes, String createdAt, String unboundExpiresAt) {
    }

    public record Capabilities(int maximumFiles, List<String> availableKinds, String asOf) {
    }

    public LearningMaterialService(LearningMaterialMapper files, LearningMaterialStorage storage, PlatformTransactionManager manager, @Qualifier("learningTaskClock") Clock clock) {
        this.files = files;
        this.storage = storage;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Capabilities capabilities() {
        outside();
        var kinds = remote(storage::kinds);
        return new Capabilities(kinds.isEmpty() ? 0 : 9, kinds, wire(now()));
    }

    public void configuredRequirements(int maximum, List<String> kinds) {
        if (maximum > 0 && (maximum > 9 || !storage.configuredKinds().containsAll(kinds))) throw unavailable();
    }

    /**
     * Bind already verified candidates against frozen kinds; no cloud calls under task locks.
     */
    public void bind(long user, long assignment, long submission, List<Long> ids, List<String> allowed) {
        required();
        for (long id : ids.stream().sorted().toList()) {
            var row = files.file(id);
            if (row == null || !allowed.contains(row.getKind()) || files.bind(id, user, assignment, submission, now()) != 1)
                throw invalid("fileIds");
        }
    }

    private record Allocation(String id, LocalDateTime expires, List<String> keys) {
    }

    public Credentials upload(long user, long assignment, List<Declaration> declarations, String key, Runnable authorize, Runnable writable) {
        outside();
        key(key);
        String storedKey = requestIdentity(key);
        validate(declarations);
        var capability = capabilities();
        if (declarations.size() > capability.maximumFiles() || declarations.stream().anyMatch(d -> !capability.availableKinds().contains(d.kind())))
            throw unavailable();
        String hash = ArticleCursor.fingerprint(declarations);
        Allocation allocation = authorized(authorize, () -> {
            var prior = files.upload(user, assignment, storedKey);
            if (prior != null) {
                if (!hash.equals(prior.fingerprint())) throw conflict("idempotency-conflict");
                if (!now().isBefore(prior.expiresAt())) throw conflict("upload-expired");
                return new Allocation(prior.uploadId(), prior.expiresAt(), files.uploadFiles(prior.uploadId()).stream().map(LearningMaterialRow::getSourceKey).toList());
            }
            writable.run();
            String upload = UUID.randomUUID().toString();
            LocalDateTime expiry = now().plusMinutes(15);
            files.insertUpload(upload, user, assignment, storedKey, hash, expiry);
            List<String> keys = new ArrayList<>();
            int index = 0;
            for (var d : declarations) {
                var row = new LearningMaterialRow();
                row.setFileId(IdWorker.getId());
                row.setAssignmentId(assignment);
                row.setOwnerUserId(user);
                row.setUploadId(upload);
                row.setFileIndex(index++);
                row.setFileName(d.fileName());
                row.setKind(d.kind());
                row.setContentType(d.contentType());
                row.setSizeBytes(d.sizeBytes());
                row.setSourceKey("learning-task/upload/" + upload + "/" + row.getFileIndex());
                row.setRetainedKey("learning-task/retained/" + row.getFileId());
                row.setCreatedAt(now());
                row.setUploadExpiresAt(expiry);
                row.setCleanupAfter(expiry.plusHours(24));
                files.insert(row);
                keys.add(row.getSourceKey());
            }
            return new Allocation(upload, expiry, List.copyOf(keys));
        });
        credentials.entrySet().removeIf(e -> !e.getValue().uploadExpiresAt().isAfter(utcNow()));
        var cached = credentials.get(allocation.id());
        if (cached != null) return cached;
        if (credentials.size() >= 10000) throw unavailable();
        if (!signing.add(allocation.id())) throw conflict("state-conflict");
        try {
            var result = remote(() -> storage.credentials(allocation.id(), allocation.keys(), utc(allocation.expires())));
            if (result == null || !allocation.id().equals(result.uploadId()) || !allocation.keys().equals(result.objectKeys()) ||
                    result.tmpSecretId() == null || result.tmpSecretKey() == null || result.sessionToken() == null ||
                    result.expiredTime() > utc(allocation.expires()).toEpochSecond(ZoneOffset.UTC) || result.expiredTime() <= clock.instant().getEpochSecond())
                throw unavailable();
            authorized(authorize, () -> null);
            credentials.put(allocation.id(), result);
            return result;
        } finally {
            signing.remove(allocation.id());
        }
    }

    public File register(long user, long assignment, String upload, int index, String key, Runnable authorize, Runnable writable) {
        outside();
        key(key);
        String storedKey = requestIdentity(key);
        if (upload == null || upload.isBlank() || upload.length() > 128 || index < 0 || index > 8)
            throw invalid("uploadId");
        var candidate = authorized(authorize, () -> {
            var rows = files.uploadFiles(upload);
            if (index >= rows.size()) throw missing();
            var row = rows.get(index);
            if (row.getAssignmentId() != assignment || row.getOwnerUserId() != user) throw missing();
            var receipt = files.receipt(user, assignment, storedKey);
            String fingerprint = ArticleCursor.fingerprint(List.of(upload, index));
            if (receipt != null) {
                if (!fingerprint.equals(receipt.fingerprint())) throw conflict("idempotency-conflict");
                return new Candidate(null, decode(receipt.resultJson()));
            }
            if ("BOUND".equals(row.getState()) || "REGISTERED".equals(row.getState())) {
                if ("REGISTERED".equals(row.getState()) && !now().isBefore(row.getUnboundExpiresAt())) throw missing();
                var original = original(row);
                files.receiptInsert(user, assignment, storedKey, fingerprint, encode(original));
                return new Candidate(null, original);
            }
            writable.run();
            if (!"UPLOADING".equals(row.getState()) || !now().isBefore(row.getCleanupAfter())) throw missing();
            return new Candidate(row, null);
        });
        if (candidate.replay() != null) return candidate.replay();
        var row = candidate.row();
        try {
            remote(() -> {
                storage.inspect(row.getSourceKey(), row.getRetainedKey(), user, declaration(row));
                return null;
            });
            return authorized(authorize, () -> {
                writable.run();
                var current = files.file(row.getFileId());
                if (current == null || current.getOwnerUserId() != user || current.getAssignmentId() != assignment)
                    throw missing();
                if ("UPLOADING".equals(current.getState())) {
                    if (files.register(current.getFileId(), now()) != 1) throw missing();
                    current = files.file(current.getFileId());
                }
                if (!Set.of("REGISTERED", "BOUND").contains(current.getState()) || "REGISTERED".equals(current.getState()) && !now().isBefore(current.getUnboundExpiresAt()))
                    throw missing();
                String fingerprint = ArticleCursor.fingerprint(List.of(upload, index));
                var prior = files.receipt(user, assignment, storedKey);
                if (prior != null) {
                    if (!prior.fingerprint().equals(fingerprint)) throw conflict("idempotency-conflict");
                    return decode(prior.resultJson());
                }
                var result = original(current);
                files.receiptInsert(user, assignment, storedKey, fingerprint, encode(result));
                return result;
            });
        } catch (RuntimeException failure) {
            // A remote copy can finish after cleanup already deleted its first snapshot. Requeue the tombstone
            // after the inspection call has returned, without ever releasing a formal historical reference.
            tx.executeWithoutResult(status -> files.retryLateInspectionCleanup(row.getFileId()));
            throw failure;
        }
    }

    private record Candidate(LearningMaterialRow row, File replay) {
    }

    public record Resource(long fileId, long assignmentId, long ownerUserId, Long submissionId, String state,
                           String retainedKey, String fileName, String kind, String contentType, int sizeBytes,
                           LocalDateTime createdAt, LocalDateTime unboundExpiresAt) {
    }

    public record Reference(long assignmentId, Long submissionId) {
    }

    public Reference reference(long id) {
        var r = files.reference(id);
        if (r == null) throw missing();
        return new Reference(r.assignmentId(), r.submissionId());
    }

    public Resource row(long id) {
        required();
        var r = files.file(id);
        if (r == null) throw missing();
        return new Resource(r.getFileId(), r.getAssignmentId(), r.getOwnerUserId(), r.getSubmissionId(), r.getState(), r.getRetainedKey(), r.getFileName(), r.getKind(), r.getContentType(), r.getSizeBytes(), r.getCreatedAt(), r.getUnboundExpiresAt());
    }

    public File metadata(Resource row, boolean candidateAllowed) {
        if (!"BOUND".equals(row.state()) && !(candidateAllowed && "REGISTERED".equals(row.state()) && now().isBefore(row.unboundExpiresAt())))
            throw missing();
        return new File(Long.toString(row.fileId()), Long.toString(row.assignmentId()), row.fileName(), row.kind(), row.contentType(), row.sizeBytes(), wire(row.createdAt()), row.unboundExpiresAt() == null ? null : wire(row.unboundExpiresAt()));
    }

    public Access access(String retained) {
        outside();
        var expires = utcNow().plusMinutes(5);
        var result = remote(() -> storage.access(retained, expires));
        if (result == null || !result.url().startsWith("https://") || result.expiresAt().isAfter(expires) || !result.expiresAt().isAfter(utcNow()))
            throw unavailable();
        return result;
    }

    public int cleanupExpiredBatch() {
        outside();
        int count = 0;
        for (long id : files.expired(now())) {
            var row = tx.execute(status -> {
                if (files.claim(id, now()) != 1) return null;
                return files.file(id);
            });
            if (row == null) continue;
            try {
                storage.delete(row.getSourceKey());
                storage.delete(row.getRetainedKey());
                tx.executeWithoutResult(status -> files.deleted(id));
                count++;
            } catch (RuntimeException failure) {
                LoggerFactory.getLogger(getClass()).warn("Private learning material cleanup deferred, fileId={}", id);
            }
        }
        return count;
    }

    private File original(LearningMaterialRow r) {
        return new File(r.getFileId().toString(), r.getAssignmentId().toString(), r.getFileName(), r.getKind(), r.getContentType(), r.getSizeBytes(), wire(r.getCreatedAt()), wire(r.getCreatedAt().plusHours(24)));
    }

    private Declaration declaration(LearningMaterialRow r) {
        return new Declaration(r.getFileName(), r.getKind(), r.getContentType(), r.getSizeBytes());
    }

    private <T> T authorized(Runnable authorize, Supplier<T> action) {
        return tx.execute(status -> {
            authorize.run();
            T value = action.get();
            authorize.run();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    authorize.run();
                }
            });
            return value;
        });
    }

    private <T> T remote(Supplier<T> action) {
        try {
            return action.get();
        } catch (ContractProblemException failure) {
            if (failure.getStatus().is5xxServerError()) throw unavailable();
            if (failure.getStatus().is4xxClientError()) throw invalid("files");
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private String encode(File file) {
        try {
            return new ObjectMapper().writeValueAsString(file);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private File decode(String json) {
        try {
            return new ObjectMapper().readValue(json, File.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void validate(List<Declaration> ds) {
        if (ds == null || ds.isEmpty() || ds.size() > 9) throw invalid("files");
        for (var d : ds) {
            if (d == null || d.kind() == null || d.contentType() == null || d.fileName() == null || d.fileName().isBlank() || d.fileName().codePointCount(0, d.fileName().length()) > 255 || d.fileName().contains("/") || d.fileName().contains("\\") || d.sizeBytes() < 1 || d.sizeBytes() > ("VIDEO".equals(d.kind()) ? 209715200 : 10485760))
                throw invalid("files");
            String extension = d.fileName().substring(d.fileName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            boolean valid = switch (d.kind()) {
                case "IMAGE" ->
                        Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "webp", "image/webp").getOrDefault(extension, "").equals(d.contentType());
                case "PDF" -> extension.equals("pdf") && d.contentType().equals("application/pdf");
                case "WORD" ->
                        Map.of("doc", "application/msword", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").getOrDefault(extension, "").equals(d.contentType());
                case "VIDEO" -> extension.equals("mp4") && d.contentType().equals("video/mp4");
                default -> false;
            };
            if (!valid) throw invalid("files");
        }
    }

    private static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) throw invalid("Idempotency-Key");
    }

    // Exact header identity must not inherit a database's case/accent-insensitive collation.
    private static String requestIdentity(String key) {
        return ArticleCursor.fingerprint(List.of("learning-private-key-v1", key));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Shanghai")).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static LocalDateTime utc(LocalDateTime time) {
        return time.atZone(ZoneId.of("Asia/Shanghai")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static String wire(LocalDateTime time) {
        return time.atZone(ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Remote private media work must run outside business transaction");
    }

    private static void required() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Private media binding requires owning transaction");
    }

    private static ContractProblemException unavailable() {
        return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE, "/problems/learning-task-dependency-unavailable", "PRIVATE_MEDIA");
    }

    private static ContractProblemException missing() {
        return new ContractProblemException(HttpStatus.NOT_FOUND, "/problems/not-found", "Resource not found");
    }

    private static ContractProblemException conflict(String code) {
        return ContractProblemException.conflict("learning-task-" + code, "Private material operation conflicts with current state");
    }

    private static ContractProblemException invalid(String field) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body", "/" + field, "INVALID_VALUE", "Invalid private material reference or declaration"));
    }
}
