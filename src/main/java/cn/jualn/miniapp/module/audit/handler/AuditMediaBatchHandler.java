package cn.jualn.miniapp.module.audit.handler;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueHandler;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueMessage;
import cn.jualn.miniapp.module.audit.converter.AuditConverter;
import cn.jualn.miniapp.module.audit.payload.AuditMediaBatchPayload;
import cn.jualn.miniapp.module.audit.payload.AuditMediaPayload;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 多媒体审核（批量）队列处理器。
 *
 * <p>单条消息携带多个媒体项，消费者内部逐个提交微信异步审核任务，降低生产端入队开销。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditMediaBatchHandler implements QueueHandler<AuditMediaBatchPayload> {

    private final AuditService auditService;
    private final AuditConverter auditConverter;
    private final UserService userService;

    @Override
    public void handle(QueueMessage<AuditMediaBatchPayload> message) {
        AuditMediaBatchPayload payload = message.getPayload();
        if (payload == null) {
            log.warn("[AuditMediaBatchHandler][忽略] payload 为空, topic={}, traceId={}",
                    message.getTopic(), message.getTraceId());
            return;
        }
        if (CollectionUtils.isEmpty(payload.getItems())) {
            log.debug("[AuditMediaBatchHandler][忽略] items 为空, targetType={}, targetId={}, traceId={}",
                    payload.getTargetType(), payload.getTargetId(), message.getTraceId());
            return;
        }

        String openid = payload.getOpenid();
        if (!StringUtils.hasText(openid) && message.getUserId() != null) {
            openid = userService.getMiniOpenid(message.getUserId());
        }

        for (AuditMediaBatchPayload.AuditMediaItem item : payload.getItems()) {
            if (item == null) {
                continue;
            }
            try {
                AuditMediaPayload single = AuditMediaPayload.builder()
                        .targetType(payload.getTargetType())
                        .targetId(payload.getTargetId())
                        .scene(payload.getScene())
                        .mediaType(item.getMediaType())
                        .mediaUrl(item.getMediaUrl())
                        .openid(openid)
                        .build();
                auditService.doMediaCheck(auditConverter.toMediaCheckBO(single));
            } catch (BusinessException e) {
                log.warn("[AuditMediaBatchHandler][业务异常] targetType={}, targetId={}, traceId={}, message={}",
                        payload.getTargetType(), payload.getTargetId(), message.getTraceId(), e.getMessage());
            } catch (Exception e) {
                log.error("[AuditMediaBatchHandler][系统异常] targetType={}, targetId={}, traceId={}",
                        payload.getTargetType(), payload.getTargetId(), message.getTraceId(), e);
                throw new RuntimeException("多媒体审核批量队列处理失败", e);
            }
        }
    }
}

