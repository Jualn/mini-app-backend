package cn.jualn.miniapp.module.audit.service;

import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.module.audit.entity.ContentAuditLog;
import cn.jualn.miniapp.module.audit.enums.AuditSourceEnum;
import cn.jualn.miniapp.module.audit.enums.AuditStatus;
import cn.jualn.miniapp.module.audit.mapper.ContentAuditLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Owns legacy automatic audit evidence, distinct from immutable content-safety review rounds. */
@Service
@RequiredArgsConstructor
public class AuditLogService {
    private final ContentAuditLogMapper logs;

    public boolean allPassed(AuditScene scene, long targetId) {
        long total = logs.selectCount(subject(scene, targetId));
        if (total <= 0 || logs.selectCount(subject(scene, targetId)
                .eq(ContentAuditLog::getFinalResult, AuditStatus.REJECT.getCode())) > 0) return false;
        return logs.selectCount(subject(scene, targetId)
                .eq(ContentAuditLog::getFinalResult, AuditStatus.PASS.getCode())) >= total;
    }

    private LambdaQueryWrapper<ContentAuditLog> subject(AuditScene scene, long targetId) {
        return new LambdaQueryWrapper<ContentAuditLog>().eq(ContentAuditLog::getTargetId, targetId)
                .eq(ContentAuditLog::getTargetType, scene.getCode());
    }

    /** Joins the private file reservation transaction; never submits remote work. */
    @Transactional(propagation = Propagation.MANDATORY)
    public long reservePrivateMaterial(long uploaderId) {
        return reservePrivateMaterial(uploaderId,AuditScene.POINTS_MATERIAL);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public long reservePrivateMaterial(long uploaderId,AuditScene scene) {
        if(scene!=AuditScene.POINTS_MATERIAL && scene!=AuditScene.LEARNING_TASK_MATERIAL) throw new IllegalArgumentException("Private material scene required");
        var log = ContentAuditLog.builder().targetType(scene.getCode())
                .targetId(uploaderId).auditSource(AuditSourceEnum.WX_AUTO.getCode())
                .finalResult(AuditStatus.PENDING.getCode())
                .createdAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))).build();
        if (logs.insert(log) != 1 || log.getId() == null)
            throw new IllegalStateException("Private material audit reservation failed");
        return log.getId();
    }

    /** Null denotes absent, mismatched or unacknowledged evidence, never a passed check. */
    public Integer privateMaterialResult(long uploaderId, long auditId) {
        return privateMaterialResult(uploaderId,auditId,AuditScene.POINTS_MATERIAL);
    }
    public Integer privateMaterialResult(long uploaderId,long auditId,AuditScene scene) {
        var result = logs.selectById(auditId);
        if (result == null || !Long.valueOf(uploaderId).equals(result.getTargetId())
                || !Integer.valueOf(scene.getCode()).equals(result.getTargetType())
                || !Integer.valueOf(AuditSourceEnum.WX_AUTO.getCode()).equals(result.getAuditSource())
                || result.getWxTraceId() == null || result.getWxTraceId().isBlank()) return null;
        return result.getFinalResult();
    }
}
