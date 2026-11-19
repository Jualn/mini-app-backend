package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import cn.jualn.miniapp.module.authorization.service.BusinessAuthorizationLease;
import cn.jualn.miniapp.module.notify.mapper.NotificationLifecycleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal recovery only. No send/retry and no new Admin API or permission catalog. */
@Service
@RequiredArgsConstructor
public class NotificationDeliveryResolutionService {
    public enum Conclusion { CONFIRMED_DELIVERED, CONFIRMED_FAILED, ACCEPT_UNKNOWN }
    private final BusinessAuthorizationLease authorization;
    private final NotificationLifecycleMapper lifecycle;

    @Transactional(rollbackFor=Exception.class, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void resolve(long deliveryId,long operatorId,Conclusion conclusion,String evidenceRef) {
        if (conclusion==null || evidenceRef==null || !evidenceRef.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("A bounded evidence reference is required");
        authorization.require(operatorId,decision->{
            if(decision.grants().stream().noneMatch(grant->decision.owns(grant.assignmentId())))
                throw new BusinessException(ResultCode.FORBIDDEN);
        });
        var delivery=lifecycle.lockDelivery(deliveryId);
        if(delivery==null) throw new IllegalArgumentException("Delivery does not exist");
        var existing=lifecycle.resolutionFor(deliveryId);
        if(existing!=null) {
            if(!conclusion.name().equals(existing.get("conclusion")) || !evidenceRef.equals(existing.get("evidence_ref")))
                throw new IllegalStateException("Delivery already has a different conclusion");
            return;
        }
        if(!"UNKNOWN".equals(delivery.getStatus())) throw new IllegalStateException("Only UNKNOWN requires reconciliation");
        lifecycle.resolution(deliveryId,operatorId,conclusion.name(),evidenceRef,NotificationObservation.now());
        if(conclusion!=Conclusion.ACCEPT_UNKNOWN)
            lifecycle.resolve(deliveryId,conclusion==Conclusion.CONFIRMED_DELIVERED?"DELIVERED":"FAILED");
    }
}
