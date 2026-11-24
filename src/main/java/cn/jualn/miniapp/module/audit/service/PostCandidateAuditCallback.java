package cn.jualn.miniapp.module.audit.service;
import cn.jualn.miniapp.module.audit.annotation.AuditTarget;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import org.springframework.stereotype.Component;
/** Persisted candidate results are read by the waiting checker; never publish from a callback. */
@Component @AuditTarget(AuditScene.POST_EDIT)
public class PostCandidateAuditCallback implements AuditResultCallback {
    @Override public void onPass(Long id,Long logId) {}
    @Override public void onReject(Long id,Long logId,String reason) {}
}
