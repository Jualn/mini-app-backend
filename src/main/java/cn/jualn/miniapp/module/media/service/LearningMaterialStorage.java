package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.media.bo.PointMaterialBO.*;
import cn.jualn.miniapp.module.media.mapper.LearningMaterialMapper;
import cn.jualn.miniapp.module.audit.service.*;
import cn.jualn.miniapp.module.audit.bo.AuditMediaCheckBO;
import cn.jualn.miniapp.module.audit.enums.*;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import cn.jualn.miniapp.third.cos.config.CosProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reuses exact private COS transport and format parsers; supplies LearningTask safety and strict codec proof.
 */
@Slf4j
@Service
public class LearningMaterialStorage {
    private final CosPointMaterialProvider transport;
    private final ManagedArticleMediaInspector inspector;
    private final String bucket;
    private final COSClient cos;
    private final LearningMaterialMapper files;
    private final AuditLogService logs;
    private final AuditService audits;
    private final UserWechatIdentityService users;
    private final TransactionTemplate tx;

    @Value("${app.learning-task.private-image-audit-enabled:}")
    private Boolean imageAuditEnabled;

    public LearningMaterialStorage(COSClient cos, CosProperties properties, ObjectMapper json, ManagedArticleMediaInspector inspector,
                                   LearningMaterialMapper files, AuditLogService logs, AuditService audits, UserWechatIdentityService users, PlatformTransactionManager manager,
                                   @Value("${app.learning-task.private-bucket:}") String bucket) {
        this.cos = cos;
        this.bucket = bucket;
        this.inspector = inspector;
        this.files = files;
        this.logs = logs;
        this.audits = audits;
        this.users = users;
        this.tx = new TransactionTemplate(manager);
        this.transport = new CosPointMaterialProvider(cos, properties, json, this::safe, bucket, inspector);
    }

    public List<String> kinds() {
        if (bucket == null || bucket.isBlank()) return List.of();
        transport.requireReady();
        // 不判断 ffprobePath 是否可用，目前没有配置
//        if (inspector.verifiedVideoAvailable()) inspector.requireVerifiedVideoAvailable();
        return configuredKinds();
    }

    public List<String> configuredKinds() {
        if (bucket == null || bucket.isBlank()) return List.of();
        var kinds = new ArrayList<>(List.of("IMAGE", "PDF", "WORD"));
        if (inspector.verifiedVideoAvailable()) kinds.add("VIDEO");
        return List.copyOf(kinds);
    }

    public Credentials credentials(String upload, List<String> keys, LocalDateTime expiryUtc) {
        return transport.credentials(upload, keys, expiryUtc);
    }

    public void inspect(String source, String retained, long actor, Declaration declaration) {
        transport.retainAndInspect(source, retained, actor, declaration, "VIDEO".equals(declaration.kind()) ? 209715200 : 10485760);
    }

    public Access access(String retained, LocalDateTime expiryUtc) {
        return transport.access(retained, expiryUtc);
    }

    public void delete(String key) {
        transport.delete(key);
    }

    private record Reservation(long audit, boolean submit) {
    }

    private void safe(long owner, String retained, Path snapshot, Declaration declaration) {
        if ("PDF".equals(declaration.kind()) || "WORD".equals(declaration.kind())) {
            new ArticleDocumentInspector().extract(snapshot, declaration.contentType());
            return;
        }
        if ("VIDEO".equals(declaration.kind())) {
            try {
                inspector.verifiedVideo(snapshot, declaration.sizeBytes());
                return;
            } catch (java.io.IOException failure) {
                throw PointMaterialProblems.unavailable(PointMaterialProblems.Dependency.PRIVATE_MEDIA);
            }
        }
        if (!"IMAGE".equals(declaration.kind())) return;
        // 关闭时 跳过微信审核
        if (!imageAuditEnabled) return;
        String openid = users.getMiniOpenid(owner);
        if (openid == null || openid.isBlank())
            throw PointMaterialProblems.unavailable(PointMaterialProblems.Dependency.CONTENT_SAFETY);
        Reservation reservation = tx.execute(status -> {
            var row = files.retained(retained);
            if (row == null || row.getOwnerUserId() != owner || !"UPLOADING".equals(row.getState()))
                throw PointMaterialProblems.unavailable(PointMaterialProblems.Dependency.CONTENT_SAFETY);
            if (row.getSafetyAuditLogId() != null) return new Reservation(row.getSafetyAuditLogId(), false);
            long audit = logs.reservePrivateMaterial(owner, AuditScene.LEARNING_TASK_MATERIAL);
            if (files.audit(row.getFileId(), audit) != 1)
                throw new IllegalStateException("Private image reservation was not accepted");
            return new Reservation(audit, true);
        });
        if (reservation.submit()) {
            String url = cos.generatePresignedUrl(bucket, retained, Date.from(Instant.now().plusSeconds(900)), HttpMethodName.GET).toExternalForm();
            if (!url.startsWith("https://"))
                throw PointMaterialProblems.unavailable(PointMaterialProblems.Dependency.PRIVATE_MEDIA);
            audits.doMediaCheck(AuditMediaCheckBO.builder().auditLogId(reservation.audit()).auditScene(AuditScene.LEARNING_TASK_MATERIAL)
                    .targetId(owner).mediaType(MediaType.IMAGE).mediaUrl(url).openid(openid).scene(3).build());
        }
        long until = System.nanoTime() + 10_000_000_000L;
        do {
            Integer result = logs.privateMaterialResult(owner, reservation.audit(), AuditScene.LEARNING_TASK_MATERIAL);
            if (Integer.valueOf(AuditStatus.PASS.getCode()).equals(result)) return;
            if (Integer.valueOf(AuditStatus.REJECT.getCode()).equals(result))
                throw PointMaterialService.invalidReference("uploadId");
            if (System.nanoTime() >= until)
                throw PointMaterialProblems.unavailable(PointMaterialProblems.Dependency.CONTENT_SAFETY);
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw PointMaterialProblems.unavailable(PointMaterialProblems.Dependency.CONTENT_SAFETY);
            }
        } while (true);
    }
}
