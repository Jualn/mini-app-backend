package cn.jualn.miniapp.module.activity.service.impl;

import cn.jualn.miniapp.common.annotation.AuditTarget;
import cn.jualn.miniapp.common.enums.AuditScene;
import cn.jualn.miniapp.module.audit.service.AuditResultCallback;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 运维直接发布后，历史审核结果不得再改变活动状态。 */
@Slf4j
@Component
@AuditTarget(AuditScene.ACTIVITY)
public class ActivityAuditCallback implements AuditResultCallback {
    @Override public void onPass(Long id, Long auditLogId) { log.info("忽略已停用的活动审核回调，id={}", id); }
    @Override public void onReject(Long id, Long auditLogId, String reason) { log.info("忽略已停用的活动审核回调，id={}", id); }
}
