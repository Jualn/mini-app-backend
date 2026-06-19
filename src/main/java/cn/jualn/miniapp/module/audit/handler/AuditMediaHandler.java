package cn.jualn.miniapp.module.audit.handler;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueHandler;
import cn.jualn.miniapp.infrastructure.queue.contract.QueueMessage;
import cn.jualn.miniapp.module.audit.converter.AuditConverter;
import cn.jualn.miniapp.module.audit.payload.AuditMediaPayload;
import cn.jualn.miniapp.module.audit.service.AuditService;
import cn.jualn.miniapp.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 多媒体审核队列处理器。
 *
 * <p>发业自此处理多媒体内容安全审核，包括审核结果的业务回调，会自动查询并调用对应 targetType 的业务回调处理。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditMediaHandler implements QueueHandler<AuditMediaPayload> {

    private final AuditService auditService;
    private final AuditConverter auditConverter;
    private final UserService userService;

    /**
     * 处理多媒体审核队列消息。
     *
     * <p>只负责提交微信异步审核任务、记录 trace 绑定，最终审核结果由微信回调接口处理。
     * 楠火缺失或业务异常不重试，系统异常时抱出并且队列框架处理重试。</p>
     *
     * @param message 队列消息，包含多媒体审核 payload
     */
    @Override
    public void handle(QueueMessage<AuditMediaPayload> message) {
        AuditMediaPayload payload = message.getPayload();
        if (payload == null) {
            log.warn("[AuditMediaHandler][忽略] payload 为空, topic={}, traceId={}",
                    message.getTopic(), message.getTraceId());
            return;
        }

        try {
            if (!StringUtils.hasText(payload.getOpenid()) && message.getUserId() != null) {
                payload.setOpenid(userService.getMiniOpenid(message.getUserId()));
            }
            // 这里只负责提交微信异步审核任务，最终结果由微信回调接口处理。
            auditService.doMediaCheck(auditConverter.toMediaCheckBO(payload));
        } catch (BusinessException e) {
            // 参数或业务状态异常通常不可重试，记录后结束本次消费
            log.warn("[AuditMediaHandler][业务异常] targetType={}, targetId={}, traceId={}, message={}",
                    payload.getTargetType(), payload.getTargetId(), message.getTraceId(), e.getMessage());
        } catch (Exception e) {
            log.error("[AuditMediaHandler][系统异常] targetType={}, targetId={}, traceId={}",
                    payload.getTargetType(), payload.getTargetId(), message.getTraceId(), e);
            throw new RuntimeException("多媒体审核队列处理失败", e);
        }
    }
}
