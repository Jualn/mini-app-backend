package cn.jualn.miniapp.module.audit.payload;

import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.infrastructure.queue.annotation.QueueTopic;
import cn.jualn.miniapp.infrastructure.queue.contract.MessagePayload;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@QueueTopic("audit.media.batch")
public class AuditMediaBatchPayload implements MessagePayload {

    private TargetType targetType;

    private Long targetId;

    private Integer scene;

    /**
     * 小程序用户 openid（可选）。若生产者未填，由消费者根据 message.userId 获取。
     */
    private String openid;

    private List<AuditMediaItem> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditMediaItem {
        private Long auditLogId;
        private String mediaUrl;
        private MediaType mediaType;
    }
}

