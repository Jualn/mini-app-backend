package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.article.service.ArticleSafetyChecks;
import cn.jualn.miniapp.module.audit.entity.MediaVideoAudit;
import cn.jualn.miniapp.module.media.bo.MediaAttachmentBO;
import cn.jualn.miniapp.third.cos.config.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class CosVideoSafetyCheckService implements VideoSafetyChecks {
    private final CosVideoAuditWriter writer;
    private final CosProperties cos;
    private final CosVideoAuditProperties policy;

    @Override public void reserve(long attachmentId,String objectKey,long sizeBytes,long operatorId) {
        if(policy.isEnabled()) ensure(attachmentId,objectKey,sizeBytes,operatorId);
    }
    @Override public void requirePassed(MediaAttachmentBO attachment,long operatorId) {
        if(!Boolean.TRUE.equals(attachment.getVerified()) || !"VIDEO".equals(attachment.getKind())
                || attachment.getId()==null || attachment.getId()<=0 || attachment.getSizeBytes()==null || attachment.getSizeBytes()<=0
                || attachment.getObjectKey()==null || attachment.getObjectKey().isBlank()) throw ArticleSafetyChecks.unavailable();
        // Basic technical verification permits playback without optional deep inspection or moderation.
        if(!policy.isEnabled()) return;
        if(attachment.getDurationSeconds()==null || !Double.isFinite(attachment.getDurationSeconds())
                || attachment.getDurationSeconds()<=0) throw ArticleSafetyChecks.unavailable();
        if(attachment.getDurationSeconds()>10000d*policy.getSnapshotIntervalSeconds()) throw ArticleSafetyChecks.unavailable();
        var row=ensure(attachment.getId(),attachment.getObjectKey(),attachment.getSizeBytes(),operatorId);
        if("REJECT".equals(row.getState())) throw ContractProblemException.conflict("content-rejected","Video was rejected by safety checks");
        if(!"PASS".equals(row.getState())) throw ArticleSafetyChecks.unavailable();
    }
    private MediaVideoAudit ensure(long attachmentId,String objectKey,long sizeBytes,long operatorId) {
        if(!policy.isPolicyConfigured() || attachmentId<=0 || operatorId<=0 || sizeBytes<=0 || objectKey==null || !objectKey.matches("article-resource/[0-9]+/[A-Za-z0-9-]+")) throw ArticleSafetyChecks.unavailable();
        var row=new MediaVideoAudit(); row.setAttachmentId(attachmentId); row.setObjectKey(objectKey); row.setSizeBytes(sizeBytes);
        row.setBucket(cos.getBucket()); row.setRegion(cos.getRegion()); row.setBizType(policy.getBizType());
        row.setSnapshotIntervalSeconds(policy.getSnapshotIntervalSeconds()); row.setOperatorId(operatorId);
        row.setPolicyKey(policyKey()); row.setDeadlineAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusMinutes(policy.getDeadlineMinutes()));
        return writer.reserve(row);
    }
    public String policyKey() {
        String value=String.join("\n","ci-video-v1",cos.getBucket(),cos.getRegion(),policy.getBizType(),policy.getPolicyRevision(),
                String.valueOf(policy.getSnapshotIntervalSeconds()),"10000","audio=1");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
