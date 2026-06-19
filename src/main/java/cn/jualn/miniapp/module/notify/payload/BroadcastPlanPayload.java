package cn.jualn.miniapp.module.notify.payload;

import cn.jualn.miniapp.infrastructure.queue.annotation.QueueTopic;
import cn.jualn.miniapp.infrastructure.queue.contract.MessagePayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 广播计划触发 Payload（延迟队列到期后发的，只带 planId）。
 * ContentBroadcastHandler 消费时从 DB 查 notify_plan 获取完整信息。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@QueueTopic("broadcast.plan")
public class BroadcastPlanPayload implements MessagePayload {
    private Long planId;
}
