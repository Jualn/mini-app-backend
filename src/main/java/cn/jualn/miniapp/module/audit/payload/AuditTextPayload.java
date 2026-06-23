package cn.jualn.miniapp.module.audit.payload;

import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.queue.annotation.QueueTopic;
import cn.jualn.miniapp.infrastructure.queue.contract.MessagePayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@QueueTopic("audit.text")
public class AuditTextPayload implements MessagePayload {
    /**
     * 空状态的审核日志 ID 用于更新审核日志中的结果
     */
    private Long auditLogId;
    /**
     * 目标类型：1-帖子 2-活动 3-考试信息 4-评论。
     */
    private TargetType targetType;

    /**
     * 目标内容 ID。
     */
    private Long targetId;

    /**
     * 待审核文本。
     */
    private String content;

    /**
     * 场景值：1-资料；2-评论；3-论坛；4-社交日志。
     */
    private Integer scene;

    /**
     * 小程序用户 openid，两小时内访问过。
     */
    private String openid;
}
