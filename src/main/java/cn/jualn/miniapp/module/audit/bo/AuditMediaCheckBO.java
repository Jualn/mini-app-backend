package cn.jualn.miniapp.module.audit.bo;

import cn.jualn.miniapp.common.enums.MediaType;
import cn.jualn.miniapp.common.enums.TargetType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多媒体审核业务对象。
 *
 * <p>用于从控制器请求或队列消息到业务层的数据转换，包含审核所需的目标信息和媒体 URL。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditMediaCheckBO {

    /**
     * 空状态的审核日志 ID 用于更新审核日志中的结果
     */
    private Long auditLogId;

    /**
     * 审核目标类型：1-帖子 2-活动 3-考试信息 4-评论。
     */
    private TargetType targetType;

    /**
     * 审核目标内容 ID。
     */
    private Long targetId;

    /**
     * 待审核媒体的公网 URL，微信服务器会在此 URL 下载媒体进行审核。
     */
    private String mediaUrl;

    /**
     * 媒体类型：1-音频，2-图片。
     */
    private MediaType mediaType;

    /**
     * 微信审核场景值：1-资料；2-评论；3-论坛；4-社交日志。
     */
    private Integer scene;

    /**
     * 小程序用户 openid，需要在两小时前登陆过小程序。
     */
    private String openid;


}
