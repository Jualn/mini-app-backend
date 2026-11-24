package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.*;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.media.bo.ArticleUploadFileBO;
import cn.jualn.miniapp.module.media.entity.*;
import cn.jualn.miniapp.module.media.mapper.*;
import cn.jualn.miniapp.third.cos.service.CosService;

import java.time.LocalDateTime;
import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ArticleMediaWriter {
    private final MediaUploadRecordMapper uploads;
    private final MediaAttachmentMapper attachments;
    private final MediaUploadRecordService lifecycle;
    private final CosService cos;
    private final ArticleAuthorization authorization;
    private final cn.jualn.miniapp.module.audit.service.VideoSafetyChecks videoSafety;

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void pending(List<String> keys, List<ArticleUploadFileBO> files, long operator) {
        authorization.require(ArticleAuthorization.Capability.MEDIA);
        List<MediaUploadRecord> records = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            var file = files.get(i);
            records.add(MediaUploadRecord.builder().objectKey(keys.get(i)).userId(operator).targetType(TargetType.ARTICLE.getCode())
                    .status(0).cleanupAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusHours(3)).retryCount(0).declaredKind(file.kind())
                    .declaredContentType(file.contentType()).declaredSizeBytes(file.sizeBytes()).build());
        }
        if (uploads.insertBatch(records) != records.size())
            throw new IllegalStateException("Article upload registration failed");
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public MediaAttachment register(long operator, String sourceKey, String snapshotKey, String kind, String name, ManagedArticleMediaInspector.Metadata metadata) {
        if (authorization.require(ArticleAuthorization.Capability.MEDIA) != operator)
            throw new IllegalStateException("Media operator mismatch");
        var source = uploads.selectForUpdate(sourceKey);
        var snapshot = uploads.selectForUpdate(snapshotKey);
        if (source != null && source.getBoundAttachmentId() != null) {
            var existing = attachments.lock(source.getBoundAttachmentId());
            AttachmentRegistrationService.requireSame(existing, kind, name);
            AttachmentRegistrationService.requireReplayable(existing);
            return existing;
        }
        requirePending(source, operator);
        requirePending(snapshot, operator);
        if (!kind.equals(source.getDeclaredKind()) || !metadata.contentType().equals(source.getDeclaredContentType()) || !Long.valueOf(metadata.sizeBytes()).equals(source.getDeclaredSizeBytes()))
            ArticleBodyCodec.invalid("#/url", "Media declaration changed");
        var resource = MediaAttachment.builder().kind(kind).type(switch(kind) {case "IMAGE"->MediaType.IMAGE.getCode();case "PDF"->MediaType.PDF.getCode();case "WORD"->MediaType.WORD.getCode();default->MediaType.URL.getCode();})
                .retention("CANDIDATE").expiresAt(source.getCleanupAfter()).deleted(false)
                .registered(true).registeredBy(operator).objectKey(snapshotKey).url(cos.buildPublicUrl(snapshotKey)).originalName(name)
                .sortOrder(0).contentType(metadata.contentType()).sizeBytes(metadata.sizeBytes()).width(metadata.width()).height(metadata.height())
                .durationSeconds(metadata.durationSeconds()).verified(true).build();
        if (attachments.insert(resource) != 1) throw new IllegalStateException("Article media registration failed");
        lifecycle.bindRegisteredAttachment(snapshot, resource.getId());
        source.setBoundAttachmentId(resource.getId());
        source.setStatus(0);
        if (uploads.updateById(source) != 1) throw new IllegalStateException("Source registration failed");
        snapshot.setBoundAttachmentId(resource.getId());
        snapshot.setCleanupAfter(source.getCleanupAfter());
        if (uploads.updateById(snapshot) != 1) throw new IllegalStateException("Snapshot registration failed");
        if ("VIDEO".equals(kind)) videoSafety.reserve(resource.getId(), snapshotKey, metadata.sizeBytes(), operator);
        return resource;
    }

    static void requirePending(MediaUploadRecord record, long operator) {
        if (record == null || !Long.valueOf(operator).equals(record.getUserId()) || !Integer.valueOf(8).equals(record.getTargetType())
                || !Integer.valueOf(0).equals(record.getStatus()) || record.getCleanupStartedAt() != null
                || record.getBoundAttachmentId() != null || record.getBoundTargetId() != null || !record.getCleanupAfter().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))))
            ArticleBodyCodec.invalid("#/url", "Upload is not available to this administrator");
    }
}
