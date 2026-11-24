package cn.jualn.miniapp.module.media.service;

import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.module.media.enums.MediaType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.audit.bo.AuditMediaCheckBO;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.service.AuditLogService;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO.Declaration;
import cn.jualn.miniapp.module.media.mapper.PointMaterialSafetyMapper;
import cn.jualn.miniapp.module.user.service.UserWechatIdentityService;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.http.HttpMethodName;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.jualn.miniapp.module.media.service.PointMaterialProblems.Dependency.CONTENT_SAFETY;
import static cn.jualn.miniapp.module.media.service.PointMaterialProblems.unavailable;

/**
 * WeChat checks images only. Documents and videos receive format validation without moderation.
 */
@Service
public class WechatPointMaterialSafety implements PointMaterialSafety {
    private final PointMaterialSafetyMapper files;
    private final AuditLogService logs;
    private final AuditService audits;
    private final UserWechatIdentityService users;
    private final COSClient cos;
    private final String bucket;
    private final TransactionTemplate tx;
    private final long waitMillis;

    @Value("${app.points.private-image-check-enabled:}")
    private Boolean imageAuditEnabled;

    public WechatPointMaterialSafety(PointMaterialSafetyMapper files, AuditLogService logs,
                                     AuditService audits, UserWechatIdentityService users, COSClient cos, PlatformTransactionManager manager,
                                     @Value("${app.points.private-bucket:}") String bucket,
                                     @Value("${app.points.image-check-wait-millis:10000}") long waitMillis) {
        this.files = files;
        this.logs = logs;
        this.audits = audits;
        this.users = users;
        this.cos = cos;
        this.bucket = bucket;
        this.tx = new TransactionTemplate(manager);
        this.waitMillis = Math.min(10000, Math.max(0, waitMillis));
    }

    @Override
    public void requireSafe(long owner, String retained, Path snapshot, Declaration declaration) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Private material checking must run outside business transactions");
        }
        if ("PDF".equals(declaration.kind())
                || "WORD".equals(declaration.kind())
                || "VIDEO".equals(declaration.kind()))
            return;
        if (!"IMAGE".equals(declaration.kind())) throw unavailable(CONTENT_SAFETY);

        if (!imageAuditEnabled) return;

        try {
            // Resolve the real uploader's mini-program identity, never the Person or current bound account.
            String openid = users.getMiniOpenid(owner);
            if (openid == null || openid.isBlank()) throw unavailable(CONTENT_SAFETY);
            Reservation reservation = tx.execute(status -> reserve(owner, retained, declaration));
            if (reservation == null) throw unavailable(CONTENT_SAFETY);
            if (reservation.submit()) {
                // The URL is transient: never store it in the audit log, file row, receipt or a job payload.
                String url = cos.generatePresignedUrl(bucket, retained, Date.from(Instant.now().plusSeconds(900)),
                        HttpMethodName.GET).toExternalForm();
                // One durable claim precedes the remote call. A timeout/crash never causes automatic resubmission.
                audits.doMediaCheck(AuditMediaCheckBO.builder().auditLogId(reservation.auditId())
                        .auditScene(AuditScene.POINTS_MATERIAL).targetId(owner).mediaType(MediaType.IMAGE)
                        .mediaUrl(url).openid(openid).scene(3).build());
            }
            requirePassed(owner, reservation.auditId());
        } catch (ContractProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // Do not retain provider exception messages: they may include a signed private URL.
            throw unavailable(CONTENT_SAFETY);
        }
    }

    private Reservation reserve(long owner, String retained, Declaration declaration) {
        var file = files.lock(retained);
        if (file == null || file.getOwnerUserId() != owner || !"UPLOADING".equals(file.getState())
                || !"IMAGE".equals(file.getKind()) || !declaration.contentType().equals(file.getContentType())
                || declaration.sizeBytes() != file.getSizeBytes()) throw unavailable(CONTENT_SAFETY);
        Long prior = files.auditId(file.getFileId());
        if (prior != null) return new Reservation(prior, false);
        long auditId = logs.reservePrivateMaterial(owner);
        if (files.bind(file.getFileId(), auditId) != 1) throw unavailable(CONTENT_SAFETY);
        return new Reservation(auditId, true);
    }

    private void requirePassed(long owner, long auditId) {
        long deadline = System.nanoTime() + waitMillis * 1_000_000;
        do {
            if (passed(owner, auditId)) return;
            if (System.nanoTime() >= deadline) break;
            try {
                Thread.sleep(Math.min(100, Math.max(1, (deadline - System.nanoTime()) / 1_000_000)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw unavailable(CONTENT_SAFETY);
            }
        } while (System.nanoTime() < deadline);
        throw unavailable(CONTENT_SAFETY);
    }

    private boolean passed(long owner, long auditId) {
        var result = logs.privateMaterialResult(owner, auditId);
        if (result == null) throw unavailable(CONTENT_SAFETY);
        if (Integer.valueOf(AuditStatus.PASS.getCode()).equals(result)) return true;
        if (Integer.valueOf(AuditStatus.REJECT.getCode()).equals(result)) {
            throw PointMaterialService.invalidReference("uploadId");
        }
        return false;
    }

    private record Reservation(long auditId, boolean submit) {
    }
}
