package cn.jualn.miniapp.module.audit.service;
import cn.jualn.miniapp.module.audit.enums.AuditScene;
import org.springframework.stereotype.Component;
/** Audit owns the immutable candidate result. Only a subsequent guarded OfficialContent write can publish it. */
@Component
@cn.jualn.miniapp.module.audit.annotation.AuditTarget(AuditScene.OFFICIAL_CONTENT_CANDIDATE)
public class OfficialContentCandidateAuditCallback implements AuditResultCallback {
 @Override public void onPass(Long id,Long logId) { }
 @Override public void onReject(Long id,Long logId,String reason) { }
}
