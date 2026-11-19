package cn.jualn.miniapp.module.media.service;
import cn.jualn.miniapp.module.audit.annotation.AuditTarget;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import org.springframework.stereotype.Component;
@Component @AuditTarget(AuditScene.LEARNING_TASK_MATERIAL)
public class LearningMaterialAuditCallback implements AuditResultCallback {
    @Override public void onPass(Long targetId,Long auditLogId) {}
    @Override public void onReject(Long targetId,Long auditLogId,String reason) {}
}
