package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.article.service.*;
import cn.jualn.miniapp.module.media.bo.*;
import cn.jualn.miniapp.module.media.mapper.*;
import cn.jualn.miniapp.module.media.converter.MediaConverter;
import cn.jualn.miniapp.third.cos.service.CosService;
import cn.jualn.miniapp.third.cos.dto.CosUploadCredentialDTO;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ArticleMediaService {
    private final MediaAttachmentMapper attachments;
    private final MediaUploadRecordMapper uploads;
    private final MediaConverter converter;
    private final CosService cos;
    private final MediaUploadRecordService lifecycle;
    private final ArticleMediaWriter writer;
    private final ManagedArticleMediaInspector inspector;
    private final ArticleAuthorization authorization;

    public void authorizeWrite() {
        authorization.require(ArticleAuthorization.Capability.MEDIA);
    }

    public void authorizeRead() {
        authorization.require(ArticleAuthorization.Capability.MEDIA_READ);
    }

    public boolean handles(String kind, String url) {
        String key = cos.resolveManagedObjectKey(url);
        return "VIDEO".equals(kind) || (key != null && key.startsWith("article/"));
    }

    public CosUploadCredentialDTO upload(List<ArticleUploadFileBO> files, long operator) {
        if (authorization.require(ArticleAuthorization.Capability.MEDIA) != operator)
            throw new IllegalStateException("Upload operator mismatch");
        if (files == null || files.isEmpty() || files.size() > 9) ArticleBodyCodec.invalid("#/files", "Use 1..9 files");
        List<String> keys = new ArrayList<>();
        for (var file : files) {
            if (file == null || file.fileName() == null || ArticleBodyCodec.blank(file.fileName()) || ArticleBodyCodec.length(file.fileName()) > 255
                    || file.sizeBytes() < 1 || !("IMAGE".equals(file.kind()) ? Set.of("image/jpeg", "image/png", "image/webp").contains(file.contentType()) && file.sizeBytes() <= 10485760L
                    : "VIDEO".equals(file.kind()) ? "video/mp4".equals(file.contentType()) && file.sizeBytes() <= 209715200L
                    : Set.of("PDF","WORD").contains(file.kind()) && ArticleDocumentInspector.type(file.kind(),file.fileName()).equals(file.contentType()) && file.sizeBytes()<=10485760L))
                ArticleBodyCodec.invalid("#/files", "Invalid file declaration");
            keys.add("article/upload/" + operator + "/" + UUID.randomUUID() + switch (file.contentType()) {
                case "image/jpeg" -> ".jpg";
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "application/pdf" -> ".pdf";
                case "application/msword" -> ".doc";
                case ArticleDocumentInspector.DOCX -> ".docx";
                default -> ".mp4";
            });
        }
        var credential = cos.generateExactUploadCredential(keys);
        if (credential == null || credential.getTmpSecretId() == null || credential.getTmpSecretId().isBlank()
                || credential.getTmpSecretKey() == null || credential.getTmpSecretKey().isBlank()
                || credential.getSessionToken() == null || credential.getSessionToken().isBlank()
                || credential.getExpiredTime() == null || credential.getExpiredTime() <= java.time.Instant.now().getEpochSecond()
                || !keys.equals(credential.getObjectKeys()))
            throw new cn.jualn.miniapp.common.exception.ExternalServiceException(
                    cn.jualn.miniapp.common.result.ResultCode.EXTERNAL_SERVICE_ERROR, "cos", "Article upload credentials are incomplete");
        writer.pending(keys, files, operator);
        return credential;
    }

    public MediaAttachmentBO register(String kind, String name, String url, long operator) {
        return prepareRegistration(kind, name, url, operator).get();
    }

    public Supplier<MediaAttachmentBO> prepareRegistration(String kind, String name, String url, long operator) {
        if (authorization.require(ArticleAuthorization.Capability.MEDIA) != operator)
            throw new IllegalStateException("Registration operator mismatch");
        if (!Set.of("IMAGE", "VIDEO", "PDF", "WORD").contains(kind) || name == null || ArticleBodyCodec.blank(name) || ArticleBodyCodec.length(name) > 200)
            ArticleBodyCodec.invalid("#", "Invalid Article attachment");
        String source = cos.resolveManagedObjectKey(url);
        if (source == null || !(source.startsWith("article/upload/" + operator + "/") || source.startsWith("article/retained/" + operator + "/")))
            ArticleBodyCodec.invalid("#/url", "Use this administrator's managed Article upload");
        var upload = uploads.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<cn.jualn.miniapp.module.media.entity.MediaUploadRecord>().eq(cn.jualn.miniapp.module.media.entity.MediaUploadRecord::getObjectKey, source));
        if (upload != null && !source.equals(upload.getObjectKey()))
            ArticleBodyCodec.invalid("#/url", "Object keys are case sensitive");
        if (upload != null && upload.getBoundAttachmentId() != null) {
            var existing = attachments.selectById(upload.getBoundAttachmentId());
            AttachmentRegistrationService.requireSame(existing, kind, name);
            return () -> {
                var current = attachments.lock(upload.getBoundAttachmentId());
                AttachmentRegistrationService.requireSame(current, kind, name);
                AttachmentRegistrationService.requireReplayable(current);
                return converter.toBO(current);
            };
        }
        ArticleMediaWriter.requirePending(upload, operator);
        if (!kind.equals(upload.getDeclaredKind()))
            ArticleBodyCodec.invalid("#/kind", "Attachment kind differs from upload declaration");
        if(Set.of("PDF","WORD").contains(kind) && (!ArticleDocumentInspector.type(kind,name).equals(upload.getDeclaredContentType()) || !ArticleDocumentInspector.type(kind,source).equals(upload.getDeclaredContentType()))) ArticleBodyCodec.invalid("#/name","Document extension must match the declared format");
        String snapshot = "article/retained/" + operator + "/" + UUID.randomUUID();
        // Persist cleanup responsibility before the remote copy; no lost response creates an untracked object.
        lifecycle.recordPending(operator, TargetType.ARTICLE, List.of(snapshot), LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusDays(1));
        cos.copyObject(source, snapshot);
        var metadata = inspector.inspect(snapshot, kind, upload.getDeclaredContentType(), upload.getDeclaredSizeBytes());
        return () -> converter.toBO(writer.register(operator, source, snapshot, kind, ArticleBodyCodec.trim(name), metadata));
    }

    public List<MediaAttachmentBO> requireAttachments(Map<Long, String> references) {
        if (references.isEmpty()) return List.of();
        var values = attachments.selectBatchIds(references.keySet());
        if (values.size() != references.size()) invalidReference();
        for (var value : values) {
            if (Boolean.TRUE.equals(value.getDeleted()) || "CANDIDATE".equals(value.getRetention()) && (value.getExpiresAt()==null || !value.getExpiresAt().isAfter(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")))) || !Boolean.TRUE.equals(value.getRegistered()) || !Boolean.TRUE.equals(value.getVerified())
                    || value.getObjectKey() == null || !value.getObjectKey().startsWith("article/retained/") || !references.get(value.getId()).equals(value.getKind())
                    || value.getSizeBytes() == null || value.getSizeBytes() <= 0
                    || !("IMAGE".equals(value.getKind()) ? Set.of("image/jpeg", "image/png", "image/webp").contains(value.getContentType()) && value.getSizeBytes() <= 10485760L
                    && value.getWidth() != null && value.getWidth() > 0 && value.getHeight() != null && value.getHeight() > 0
                    : Set.of("PDF","WORD").contains(value.getKind()) ? ArticleDocumentInspector.type(value.getKind(),value.getOriginalName()).equals(value.getContentType()) && value.getSizeBytes()<=10485760L
                    : "VIDEO".equals(value.getKind()) && "video/mp4".equals(value.getContentType()) && value.getSizeBytes() <= 209715200L
                    && ((value.getWidth() == null && value.getHeight() == null && value.getDurationSeconds() == null)
                    || (value.getWidth() != null && value.getWidth() > 0 && value.getHeight() != null && value.getHeight() > 0
                    && value.getDurationSeconds() != null && Double.isFinite(value.getDurationSeconds()) && value.getDurationSeconds() > 0))))
                invalidReference();
        }
        Map<Long, MediaAttachmentBO> ordered = new HashMap<>();
        converter.toBOList(values).forEach(a -> ordered.put(a.getId(), a));
        return references.keySet().stream().map(ordered::get).toList();
    }

    public List<MediaAttachmentBO> requireDocuments(List<Long> ids) {
        var refs=new LinkedHashMap<Long,String>();
        if(ids.isEmpty()) return List.of();
        var rows=attachments.selectBatchIds(ids);
        if(rows.size()!=ids.size()) invalidReference();
        for(var row:rows) {
            if(!"PDF".equals(row.getKind()) && !"WORD".equals(row.getKind())) invalidReference();
            refs.put(row.getId(),row.getKind());
        }
        var values=requireAttachments(refs).stream().collect(java.util.stream.Collectors.toMap(MediaAttachmentBO::getId,java.util.function.Function.identity()));
        return ids.stream().map(values::get).toList();
    }
    public String publicationDocumentText(List<MediaAttachmentBO> files) {
        var text=new StringBuilder();
        for(var file:files) {
            text.append(file.getOriginalName()).append('\n').append(inspector.documentText(file.getObjectKey(),file.getContentType(),file.getSizeBytes())).append('\n');
            if(text.length()>1000000) ArticleBodyCodec.invalid("#/attachmentIds","Document text exceeds publication inspection limits");
        }
        return text.toString();
    }
    private void invalidReference() {
        throw ContractProblemException.validation(new ContractProblemException.Violation("body", "#/body", "INVALID_REFERENCE", "Use registered and verified Article media of the correct kind"));
    }
}
