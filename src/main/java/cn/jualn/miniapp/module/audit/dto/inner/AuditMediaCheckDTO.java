package cn.jualn.miniapp.module.audit.dto.inner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多媒体审核请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditMediaCheckDTO {

    /**
     * 目标类型：1-帖子 2-活动 3-考试信息 4-评论。
     */
    private Integer targetType;

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
    private Integer mediaType;

    /**
     * 场景值：1-资料；2-评论；3-论坛；4-社交日志。
     */
    private Integer scene;

    /**
     * 小程序用户 openid，两小时前登陆过小程序。
     */
    private String openid;
}
