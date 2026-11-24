package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO.*;
import cn.jualn.miniapp.module.media.entity.PointMaterialRow;
import cn.jualn.miniapp.module.media.mapper.PointMaterialMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

import static cn.jualn.miniapp.module.media.service.PointMaterialProblems.*;

/**
 * Owns private material retention. Remote storage IO never runs in the caller's accounting transaction.
 */
@Service
public class PointMaterialService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PointMaterialService.class);
    private final PointMaterialMapper files;
    private final PointMaterialProvider provider;
    private final TransactionTemplate tx;
    private final Clock clock;
    private org.springframework.beans.factory.ObjectProvider<PointMaterialAdmission> admission;

    @org.springframework.beans.factory.annotation.Autowired
    void admission(org.springframework.beans.factory.ObjectProvider<PointMaterialAdmission> provider) {
        admission = provider;
    }

    private <T> T admitted(long user, java.util.function.Supplier<T> action) {
        if (admission == null || admission.getIfAvailable() == null) throw unavailable(Dependency.BUSINESS_ELIGIBILITY);
        return tx.execute(status -> {
            var policy = admission.getObject();
            policy.begin(user);
            var result = action.get();
            policy.finish(user);
            return result;
        });
    }

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    // Credentials are intentionally volatile: never persist secrets in a receipt or log.
    private final ConcurrentHashMap<String, Credentials> credentials = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 1000)
    public void discardExpiredCredentials() {
        var at = now();
        credentials.entrySet().removeIf(e -> !e.getValue().uploadExpiresAt().isAfter(at));
    }

    @jakarta.annotation.PreDestroy
    public void destroyCredentials() {
        credentials.clear();
    }

    public PointMaterialService(PointMaterialMapper files, PointMaterialProvider provider, PlatformTransactionManager manager,
                                @Qualifier("applicationClock") Clock clock) {
        this.files = files;
        this.provider = provider;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Credentials upload(long user, List<Declaration> declarations, String key) {
        outsideTransaction();
        key(key);
        validate(declarations);
        LocalDateTime at = now().withNano(0);
        credentials.entrySet().removeIf(e -> !e.getValue().uploadExpiresAt().isAfter(at));
        if (credentials.size() >= 10000) throw unavailable(Dependency.PRIVATE_MEDIA);
        String fingerprint = hash(declarations);
        String id = UUID.randomUUID().toString();
        LocalDateTime expiry = at.plusMinutes(15);
        Allocation allocation;
        try {
            allocation = admitted(user, () -> {
                var prior = files.upload(user, key);
                if (prior != null) {
                    same(prior.fingerprint(), fingerprint);
                    if (!prior.expiresAt().isAfter(at)) throw conflict("upload-expired");
                    var cached = credentials.get(prior.uploadId());
                    if (cached == null) throw conflict("operation-in-progress");
                    return new Allocation(cached, List.of());
                }
                files.insertUpload(id, user, key, fingerprint, expiry);
                var keys = new ArrayList<String>();
                for (int index = 0; index < declarations.size(); index++) {
                    var declaration = declarations.get(index);
                    var row = new PointMaterialRow();
                    row.setFileId(UUID.randomUUID().toString());
                    row.setUploadId(id);
                    row.setFileIndex(index);
                    row.setOwnerUserId(user);
                    row.setFileName(declaration.fileName());
                    row.setKind(declaration.kind());
                    row.setContentType(declaration.contentType());
                    row.setSizeBytes(declaration.sizeBytes());
                    row.setSourceKey("points/upload/" + user + "/" + id + "/" + index);
                    row.setRetainedKey("points/retained/" + row.getFileId());
                    row.setState("UPLOADING");
                    row.setUploadExpiresAt(expiry);
                    row.setCleanupAfter(expiry.plusHours(24));
                    files.insertFile(row);
                    keys.add(row.getSourceKey());
                }
                return new Allocation(null, List.copyOf(keys));
            });
        } catch (org.springframework.dao.DuplicateKeyException |
                 org.springframework.dao.PessimisticLockingFailureException concurrent) {
            throw conflict("operation-in-progress");
        }
        if (allocation.cached() != null) return allocation.cached();
        var result = provider.credentials(id, allocation.keys(), expiry);
        if (result == null || !id.equals(result.uploadId()) || !allocation.keys().equals(result.objectKeys())
                || result.expiredTime() != result.uploadExpiresAt().toEpochSecond(ZoneOffset.UTC) || result.uploadExpiresAt().isAfter(expiry)
                || result.tmpSecretId() == null || result.tmpSecretKey() == null || result.sessionToken() == null)
            throw unavailable(Dependency.PRIVATE_MEDIA);
        if (!result.uploadExpiresAt().isAfter(now())) throw conflict("upload-expired");
        admitted(user, () -> {
            files.uploadExpiry(id, result.uploadExpiresAt());
            files.fileExpiry(id, result.uploadExpiresAt());
            return null;
        });
        credentials.put(id, result);
        return result;
    }

    private record Allocation(Credentials cached, List<String> keys) {
    }

    public File register(long user, String upload, int index, String key) {
        outsideTransaction();
        key(key);
        if (upload == null || index < 0 || index > 8) throw invalid("uploadId");
        String fingerprint = hash(List.of(upload, index));
        var candidate = admitted(user, () -> {
            var prior = files.receipt(user, key);
            if (prior != null) {
                same(prior.fingerprint(), fingerprint);
                var result = decode(prior.resultJson());
                owned(user, result.fileId());
                return new Candidate(null, result);
            }
            var row = files.index(upload, index);
            if (row == null || row.getOwnerUserId() != user || !row.getUploadExpiresAt().plusHours(24).isAfter(now())
                    || !row.getCleanupAfter().isAfter(now()) || "DELETED".equals(row.getState()) || "CLEANING".equals(row.getState()))
                throw missing();
            if (!"UPLOADING".equals(row.getState())) return new Candidate(row, null);
            return new Candidate(row, null);
        });
        if (candidate.replay() != null) return candidate.replay();
        var row = candidate.row();
        if ("UPLOADING".equals(row.getState()))
            provider.retainAndInspect(row.getSourceKey(), row.getRetainedKey(), user, declaration(row));
        try {
            return admitted(user, () -> {
                var current = files.file(row.getFileId());
                if (current == null || current.getOwnerUserId() != user || "DELETED".equals(current.getState()) || "CLEANING".equals(current.getState()))
                    throw missing();
                if ("UPLOADING".equals(current.getState())) {
                    if (files.registered(current.getFileId(), now()) != 1) throw missing();
                    current = files.file(current.getFileId());
                }
                if (!"REGISTERED".equals(current.getState()) && !"BOUND".equals(current.getState()))
                    throw unavailable(Dependency.PRIVATE_MEDIA);
                // upload/index has a permanent original registration representation, independent of binding.
                var original = new File(current.getFileId(), user, current.getFileName(), current.getKind(), current.getContentType(), current.getSizeBytes(), current.getCreatedAt(), null, current.getCreatedAt().plusHours(24));
                var receipt = files.receipt(user, key);
                if (receipt != null) {
                    same(receipt.fingerprint(), fingerprint);
                    return decode(receipt.resultJson());
                }
                files.insertReceipt(user, key, fingerprint, current.getFileId(), encode(original));
                return original;
            });
        } catch (DuplicateKeyException | PessimisticLockingFailureException concurrent) {
            throw conflict("operation-in-progress");
        }
    }

    private record Candidate(PointMaterialRow row, File replay) {
    }

    public File read(long user, String id) {
        return tx.execute(status -> {
            var row = owned(user, id);
            return metadata(row);
        });
    }

    /**
     * Trusted association only; authorization of this application is performed by Points owner.
     */
    public File bound(String id) {
        return tx.execute(status -> {
            var row = files.file(id);
            if (row == null || !"BOUND".equals(row.getState()) || row.getApplicationId() == null) throw missing();
            return metadata(row);
        });
    }

    /**
     * Internal Points lookup. Caller authorizes either the original draft account or persisted application subject.
     */
    public File candidate(String id) {
        requiredTransaction();
        var row = files.file(id);
        if (row == null || !("BOUND".equals(row.getState()) || "REGISTERED".equals(row.getState()) && row.getUnboundExpiresAt() != null && row.getUnboundExpiresAt().isAfter(now())))
            throw missing();
        return metadata(row);
    }

    public Access draftAccess(long user, String id) {
        requiredTransaction();
        var row = owned(user, id);
        if (row.getApplicationId() != null) throw missing();
        return provider.access(row.getRetainedKey(), now().plusMinutes(5));
    }

    public Access access(long user, String id) {
        outsideTransaction();
        var row = tx.execute(status -> owned(user, id));
        return provider.access(row.getRetainedKey(), now().plusMinutes(5));
    }

    public Access reviewAccess(long actor, String id, long application, long owner) {
        // Caller holds the owning Points authorization context through audit and signature issuance.
        requiredTransaction();
        var row = files.file(id);
        if (row == null || row.getOwnerUserId() != owner || !Objects.equals(row.getApplicationId(), application) || !"BOUND".equals(row.getState()))
            throw missing();
        var at = now();
        files.audit(actor, id, application, at);
        return provider.access(row.getRetainedKey(), at.plusMinutes(5));
    }

    public void bind(long user, long application, List<String> ids) {
        requiredTransaction();
        var sorted = ids.stream().distinct().sorted().toList();
        if (sorted.size() != ids.size() || sorted.isEmpty() || sorted.size() > 9) throw invalidReference("fileIds");
        for (String id : sorted) {
            var row = files.file(id);
            if (row == null || row.getOwnerUserId() != user || files.bind(id, user, application, now()) != 1)
                throw invalidReference("fileIds");
        }
    }

    public void require(long user, long application, List<String> ids) {
        requiredTransaction();
        if (ids == null || ids.isEmpty() || ids.size() > 9 || ids.stream().distinct().count() != ids.size())
            throw unavailable(Dependency.PRIVATE_MEDIA);
        for (String id : ids.stream().distinct().sorted().toList()) {
            var row = files.file(id);
            if (row == null || row.getOwnerUserId() != user || !Objects.equals(row.getApplicationId(), application) || !"BOUND".equals(row.getState()))
                throw unavailable(Dependency.PRIVATE_MEDIA);
        }
    }

    /**
     * Existing scheduler calls this bounded, durable claim/retry path. Bound history is never eligible.
     */
    public int cleanupExpiredBatch() {
        outsideTransaction();
        int cleaned = 0;
        for (var row : files.expiredBoundSources(now())) {
            try {
                provider.delete(row.getSourceKey());
                tx.executeWithoutResult(status -> files.sourceDeleted(row.getFileId()));
                cleaned++;
            } catch (RuntimeException failed) {
                log.warn("Private POINTS source cleanup deferred, fileId={}", row.getFileId());
            }
        }
        for (var row : files.expired(now())) {
            boolean claimed = Boolean.TRUE.equals(tx.execute(status -> files.claim(row.getFileId(), now()) == 1));
            if (!claimed) continue;
            try {
                provider.delete(row.getSourceKey());
                provider.delete(row.getRetainedKey());
                tx.executeWithoutResult(status -> files.deleted(row.getFileId()));
                cleaned++;
            } catch (RuntimeException failed) {
                // CLEANING remains a durable retry intent; no signatures, keys or provider exception body in logs.
                log.warn("Private POINTS material cleanup deferred, fileId={}", row.getFileId());
            }
        }
        return cleaned;
    }

    private PointMaterialRow owned(long user, String id) {
        var row = files.file(id);
        if (row == null || row.getOwnerUserId() != user || !("BOUND".equals(row.getState()) || "REGISTERED".equals(row.getState()) && row.getUnboundExpiresAt() != null && row.getUnboundExpiresAt().isAfter(now())))
            throw missing();
        return row;
    }

    private File metadata(PointMaterialRow r) {
        return new File(r.getFileId(), r.getOwnerUserId(), r.getFileName(), r.getKind(), r.getContentType(), r.getSizeBytes(), r.getCreatedAt(), r.getApplicationId(), r.getUnboundExpiresAt());
    }

    private Declaration declaration(PointMaterialRow r) {
        return new Declaration(r.getFileName(), r.getKind(), r.getContentType(), r.getSizeBytes());
    }

    private LocalDateTime now() {
        var instant = clock.instant();
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).withNano((instant.getNano() / 1000) * 1000);
    }

    private void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable(Dependency.PRIVATE_MEDIA);
    }

    private void requiredTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable(Dependency.PRIVATE_MEDIA);
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private File decode(String value) {
        try {
            return json.readValue(value, File.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw unavailable(Dependency.PRIVATE_MEDIA);
        }
    }

    private void same(String a, String b) {
        if (!Objects.equals(a, b)) throw conflict("idempotency-conflict");
    }

    private void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) throw invalidParameter("header", "Idempotency-Key");
    }

    private void validate(List<Declaration> values) {
        if (values == null || values.isEmpty() || values.size() > 9) throw invalid("files");
        for (var d : values) {
            if (d == null || d.fileName() == null || d.fileName().isBlank() || d.fileName().length() > 255 || d.sizeBytes() < 1 || d.sizeBytes() > 10485760)
                throw invalid("files");
            boolean allowed = switch (d.kind() == null ? "" : d.kind()) {
                case "IMAGE" -> Set.of("image/jpeg", "image/png", "image/webp").contains(d.contentType());
                case "PDF" -> "application/pdf".equals(d.contentType());
                case "WORD" ->
                        Set.of("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(d.contentType());
                case "VIDEO" -> "video/mp4".equals(d.contentType());
                default -> false;
            };
            if (!allowed) throw invalid("contentType");
        }
    }

    public static ContractProblemException invalidReference(String field) {
        return new ContractProblemException(HttpStatus.BAD_REQUEST, "/problems/points-file-unavailable", "Proof material is unavailable", List.of(new ContractProblemException.Violation("body", "/" + field, "INVALID_REFERENCE", "Proof material is unavailable")));
    }
}
