package cn.jualn.miniapp.module.audit.payload;

import cn.jualn.miniapp.common.enums.MediaType;
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
@QueueTopic("audit.media")
public class AuditMediaPayload implements MessagePayload {

    /**
     * 目标类型：1-帖子 2-活动 3-考试信息 4-评论。
     */
    private TargetType targetType;

    /**
     * 目标内容 ID。
     */
    private Long targetId;

    /**
     * 待审核媒体公网 URL。
     */
    private String mediaUrl;

    /**
     * 媒体类型：1-音频，2-图片。
     */
    private MediaType mediaType;

    /**
     * 场景值：1-资料；2-评论；3-论坛；4-社交日志。
     */
    private Integer scene;

    /**
     * 小程序用户 openid，两小时内访问过。
     */
    private String openid;
}
