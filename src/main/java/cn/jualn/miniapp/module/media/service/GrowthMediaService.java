package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.bo.GrowthMediaBO.*;
import cn.jualn.miniapp.module.media.bo.AttachmentItemBO;
import cn.jualn.miniapp.module.media.entity.GrowthMediaRow;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import cn.jualn.miniapp.module.media.mapper.*;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.third.cos.service.CosService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

/**
 * Owns private growth candidates and immutable historical references; authorization remains with the Post owner.
 */
@Service
@RequiredArgsConstructor
public class GrowthMediaService {
    private final GrowthMediaMapper contexts;
    private final MediaUploadRecordMapper records;
    private final MediaAttachmentMapper attachments;
    private final MediaUploadRecordService uploads;
    private final MediaDeliveryUrls urls;
    private final CosService storage;
    private final PlatformTransactionManager transactions;

    public Upload upload(Scope scope, List<Declaration> declarations, Runnable authorize) {
        validate(declarations);
        var rows = new TransactionTemplate(transactions).execute(tx -> {
            authorize.run();
            var now = now();
            String id = UUID.randomUUID().toString();
            var result = new ArrayList<GrowthMediaRow>();
            int index = 0;
            for (var d : declarations) {
                var r = new GrowthMediaRow();
                r.setUploadId(id);
                r.setFileIndex(index++);
                r.setOwnerUserId(scope.user());
                r.setPersonId(scope.person());
                r.setOrganizationId(scope.organization());
                r.setRecordType(scope.type());
                r.setEditingPostId(scope.post());
                r.setSourceKey("private/growth-upload/" + scope.user() + "/" + id + "/" + r.getFileIndex());
                r.setOriginalName(d.originalName());
                r.setContentType(d.contentType());
                r.setSizeBytes(d.sizeBytes());
                r.setCreatedAt(now);
                r.setExpiresAt(now.plusHours(24));
                contexts.insert(r);
                result.add(r);
            }
            uploads.recordPending(scope.user(), TargetType.POST, result.stream().map(GrowthMediaRow::getSourceKey).toList(), now.plusHours(24));
            return result;
        });
        if (rows == null || rows.isEmpty() || !now().isBefore(rows.get(0).getExpiresAt()))
            throw conflict("growth-state-conflict");
        // Exact-key STS forbids overwrite and public ACL. No cloud calls under Person or Post locks.
        cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO credential;
        try {
            credential = storage.generateExactUploadCredential(rows.stream().map(GrowthMediaRow::getSourceKey).toList());
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        new TransactionTemplate(transactions).executeWithoutResult(tx -> authorize.run());
        return new Upload(rows.get(0).getUploadId(), Instant.ofEpochSecond(credential.getExpiredTime()).atOffset(ZoneOffset.ofHours(8)), offset(rows.get(0).getExpiresAt()),
                new Credentials(credential.getBucket(), credential.getRegion(), credential.getObjectKeys(), credential.getTmpSecretId(), credential.getTmpSecretKey(), credential.getSessionToken(), credential.getExpiredTime(), null));
    }

    public File register(Scope scope, String upload, int index, Runnable authorize) {
        if (upload == null || index < 0 || index > 8) throw invalid();
        var row = new TransactionTemplate(transactions).execute(tx -> {
            authorize.run();
            var r = contexts.lock(upload, index);
            require(r, scope);
            if (r.getBoundPostId() == null && !now().isBefore(r.getRegisteredAt() == null ? r.getExpiresAt() : r.getUnboundExpiresAt()))
                throw missing();
            if (r.getMediaId() == null) {
                String snapshot = "private/growth-effective/" + scope.user() + "/" + upload + "/" + index + "/" + UUID.randomUUID();
                uploads.recordPending(scope.user(), TargetType.POST, List.of(snapshot), r.getExpiresAt());
                var allocated = records.selectForUpdate(snapshot);
                contexts.register(upload, index, allocated.getId());
                r.setMediaId(allocated.getId());
            }
            return r;
        });
        if (row == null) throw missing();
        var allocated = records.selectById(row.getMediaId());
        if (allocated == null) throw missing();
        String snapshot = allocated.getObjectKey();
        if (!"VERIFIED".equals(allocated.getDeclaredKind())) {
            try {
                storage.copyObject(row.getSourceKey(), snapshot);
                var checked = new ManagedAttachmentInspector(storage).inspect(snapshot, "IMAGE");
                if (!row.getContentType().equals(checked.contentType()) || row.getSizeBytes() != checked.sizeBytes())
                    throw invalid();
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    authorize.run();
                    var current = contexts.lock(upload, index);
                    require(current, scope);
                    var bytes = records.selectForUpdate(snapshot);
                    if (bytes == null || bytes.getStatus() != 0 || bytes.getCleanupStartedAt() != null || !now().isBefore(current.getExpiresAt()))
                        throw missing();
                    bytes.setDeclaredKind("VERIFIED");
                    bytes.setDeclaredContentType(checked.contentType());
                    bytes.setDeclaredSizeBytes(checked.sizeBytes());
                    records.updateById(bytes);
                });
            } catch (ContractProblemException failure) {
                if (failure.getStatus().is5xxServerError()) throw unavailable();
                throw failure;
            } catch (cn.jualn.miniapp.common.exception.ExternalServiceException failure) {
                throw unavailable();
            }
        }
        return new TransactionTemplate(transactions).execute(tx -> {
            authorize.run();
            var current = contexts.lock(upload, index);
            require(current, scope);
            if (current.getBoundPostId() == null && !now().isBefore(current.getRegisteredAt() == null ? current.getExpiresAt() : current.getUnboundExpiresAt()))
                throw missing();
            var bytes = records.selectForUpdate(snapshot);
            if (bytes == null || !"VERIFIED".equals(bytes.getDeclaredKind()) || bytes.getCleanupStartedAt() != null)
                throw missing();
            if (current.getRegisteredAt() == null) {
                var at = now();
                if (contexts.completeRegistration(upload, index, at) != 1) throw missing();
                current.setRegisteredAt(at);
                current.setUnboundExpiresAt(at.plusHours(24));
                bytes.setCleanupAfter(at.plusHours(24));
                records.updateById(bytes);
            }
            return file(current, "CHECKING");
        });
    }

    public Scope scope(long actor, String upload, int index) {
        var rows = contexts.upload(upload);
        if (index < 0 || index >= rows.size()) throw missing();
        var r = rows.get(index);
        if (r.getOwnerUserId() != actor) throw missing();
        return new Scope(actor, r.getPersonId(), r.getOrganizationId(), r.getRecordType(), r.getEditingPostId());
    }

    public List<AttachmentItemBO> prepare(Scope scope, List<Long> ids) {
        if (ids == null || ids.size() > 9 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size())
            throw invalid();
        var result = new ArrayList<AttachmentItemBO>();
        int order = 0;
        for (long id : ids) {
            var r = contexts.file(id);
            require(r, scope);
            if (r.getBoundPostId() != null && !Objects.equals(r.getBoundPostId(), scope.post()) || r.getBoundPostId() == null && (r.getRegisteredAt() == null || r.getUnboundExpiresAt() == null || !now().isBefore(r.getUnboundExpiresAt())))
                throw missing();
            var file = records.selectById(id);
            if (file == null || !"VERIFIED".equals(file.getDeclaredKind()) || file.getCleanupStartedAt() != null || file.getStatus() != 0 && file.getStatus() != 1)
                throw missing();
            result.add(AttachmentItemBO.builder().type(MediaType.IMAGE).objectKey(file.getObjectKey()).url(urls.forKey(file.getObjectKey())).originalName(r.getOriginalName()).sortOrder(order++).build());
        }
        return List.copyOf(result);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(Scope scope, long post, List<Long> ids) {
        var items = prepare(scope, ids);
        attachments.delete(new LambdaQueryWrapper<MediaAttachment>().eq(MediaAttachment::getTargetType, 1).eq(MediaAttachment::getTargetId, post));
        for (int i = 0; i < ids.size(); i++) {
            var row = contexts.file(ids.get(i));
            var locked = contexts.lock(row.getUploadId(), row.getFileIndex());
            require(locked, scope);
            if (locked.getBoundPostId() == null)
                uploads.bindPending(scope.user(), TargetType.POST, post, List.of(items.get(i).getObjectKey()));
            if (contexts.bind(ids.get(i), post) != 1) throw missing();
            var item = items.get(i);
            attachments.insert(MediaAttachment.builder().targetType(1).targetId(post).type(MediaType.IMAGE.getCode()).kind("IMAGE").objectKey(item.getObjectKey()).url(item.getUrl()).originalName(item.getOriginalName()).sortOrder(i).deleted(false).build());
        }
    }

    public List<Long> currentIds(long post) {
        return attachments.selectList(new LambdaQueryWrapper<MediaAttachment>().eq(MediaAttachment::getTargetType, 1).eq(MediaAttachment::getTargetId, post).orderByAsc(MediaAttachment::getSortOrder)).stream()
                .map(a -> records.selectOne(new LambdaQueryWrapper<cn.jualn.miniapp.module.media.entity.MediaUploadRecord>().eq(cn.jualn.miniapp.module.media.entity.MediaUploadRecord::getObjectKey, a.getObjectKey())).getId()).toList();
    }

    public List<File> current(long post, String safety) {
        return currentIds(post).stream().map(id -> file(contexts.file(id), safety)).toList();
    }

    /**
     * Metadata for already-authorized immutable review references, including comment images.
     */
    public List<File> evidence(List<String> references, String safety) {
        return references.stream().map(reference -> {
            Long id = urls.fileId(reference);
            if (id == null) throw missing();
            var r = records.selectById(id);
            if (r == null || r.getStatus() != 1 || !"VERIFIED".equals(r.getDeclaredKind()) || !r.getObjectKey().startsWith("private/"))
                throw missing();
            var context = contexts.file(id);
            return new File(id, "IMAGE", context == null ? "图片" : context.getOriginalName(), r.getDeclaredContentType(), r.getDeclaredSizeBytes(), safety, urls.adminVariant(urls.forKey(r.getObjectKey())), null);
        }).toList();
    }

    private File file(GrowthMediaRow r, String safety) {
        if (r == null || r.getMediaId() == null) throw missing();
        var media = records.selectById(r.getMediaId());
        if (media == null) throw missing();
        return new File(r.getMediaId(), "IMAGE", r.getOriginalName(), r.getContentType(), r.getSizeBytes(), safety, urls.forKey(media.getObjectKey()), r.getBoundPostId() == null ? offset(r.getUnboundExpiresAt()) : null);
    }

    private void require(GrowthMediaRow r, Scope s) {
        if (r == null || r.getOwnerUserId() != s.user() || !r.getPersonId().equals(s.person()) || !r.getOrganizationId().equals(s.organization()) || !r.getRecordType().equals(s.type()) || !Objects.equals(r.getEditingPostId(), s.post()) && !(r.getEditingPostId() == null && Objects.equals(r.getBoundPostId(), s.post())))
            throw missing();
    }

    public boolean reserved(String key) {
        return key != null && contexts.reserved(key) > 0;
    }

    public boolean candidateAllowed(long id, long actor, java.util.function.Predicate<Scope> authorize) {
        var r = contexts.file(id);
        return r != null && r.getBoundPostId() == null && r.getOwnerUserId() == actor && r.getRegisteredAt() != null && r.getUnboundExpiresAt() != null && now().isBefore(r.getUnboundExpiresAt()) && authorize.test(new Scope(actor, r.getPersonId(), r.getOrganizationId(), r.getRecordType(), r.getEditingPostId()));
    }

    public boolean isGrowth(long id) {
        return contexts.file(id) != null;
    }

    public boolean retainedFor(long id, long post) {
        var row = contexts.file(id);
        return row != null && Objects.equals(row.getBoundPostId(), post);
    }

    private static void validate(List<Declaration> files) {
        if (files == null || files.isEmpty() || files.size() > 9) throw invalid();
        for (var f : files) {
            if (f == null || f.kind() == null || f.originalName() == null || f.originalName().isBlank() || f.originalName().length() > 255 || f.sizeBytes() < 1 || f.sizeBytes() > ("VIDEO".equals(f.kind()) ? 209715200 : 10485760))
                throw invalid();
            if ("VIDEO".equals(f.kind())) {
                if (!"video/mp4".equals(f.contentType())) throw invalid();
                throw unavailable();
            }
            if (!"IMAGE".equals(f.kind()) || !Set.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(Objects.toString(f.contentType(), "")))
                throw invalid();
        }
    }

    public static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}"))
            throw ContractProblemException.validation(new ContractProblemException.Violation("header", "Idempotency-Key", "INVALID_VALUE", "Use a 16..128 character growth intent key"));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static OffsetDateTime offset(LocalDateTime t) {
        return t.atOffset(ZoneOffset.ofHours(8));
    }

    private static ContractProblemException missing() {
        return new ContractProblemException(org.springframework.http.HttpStatus.NOT_FOUND, "/problems/growth-media-not-found", "Growth media unavailable");
    }

    private static ContractProblemException invalid() {
        return ContractProblemException.validation(new ContractProblemException.Violation("body", "/mediaIds", "INVALID_VALUE", "Invalid growth media declaration or reference"));
    }

    private static ContractProblemException conflict(String code) {
        return ContractProblemException.conflict(code, "Growth media intent conflicts");
    }

    private static ContractProblemException unavailable() {
        return new ContractProblemException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "/problems/growth-media-unavailable", "Effective growth video inspection is unavailable");
    }
}
