package cn.jualn.miniapp.module.audit.handler;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueHandler;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueMessage;
import cn.jualn.miniapp.module.audit.converter.AuditConverter;
import cn.jualn.miniapp.module.audit.payload.AuditTextPayload;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 文本审核队列处理器。
 *
 * <p>发业自此处理文本内容安全审核，包括审核幸的业务回调，会自动查询并调用对应 targetType 的业务回调处理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditTextHandler implements QueueHandler<AuditTextPayload> {

    private final AuditService auditService;
    private final AuditConverter auditConverter;
    private final UserService userService;

    /**
     * 处理文本审核队列消息。
     *
     * <p>橁火缺失或业务异常不重试，系统异常时抱出并且队列框架处理重试。</p>
     *
     * @param message 队列消息，包含 文本审核 payload
     */
    @Override
    public void handle(QueueMessage<AuditTextPayload> message) {
        AuditTextPayload payload = message.getPayload();
        if (payload == null) {
            log.warn("[AuditTextHandler][忽略] payload 为空, topic={}, traceId={}",
                    message.getTopic(), message.getTraceId());
            return;
        }

        try {
            // message中绑定的是当时发送请求的用户，所有在这获取openid，减少业务请求的消耗
            payload.setOpenid(userService.getMiniOpenid(message.getUserId()));
            auditService.processTextAudit(
                    auditConverter.toTextCheckBO(payload)
            );
        } catch (BusinessException e) {
            // 业务异常通常是参数或状态问题，重试意义不大，记录后结束本次消费
            log.warn("[AuditTextHandler][业务异常] auditScene={}, targetId={}, traceId={}, message={}",
                    payload.getAuditScene(), payload.getTargetId(), message.getTraceId(), e.getMessage());
        } catch (Exception e) {
            log.error("[AuditTextHandler][系统异常] auditScene={}, targetId={}, traceId={}",
                    payload.getAuditScene(), payload.getTargetId(), message.getTraceId(), e);
            throw new RuntimeException("文本审核队列处理失败", e);
        }

    }
}
