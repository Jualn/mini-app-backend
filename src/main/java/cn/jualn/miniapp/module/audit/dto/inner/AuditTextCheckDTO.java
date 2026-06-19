package cn.jualn.miniapp.module.audit.dto.inner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文本审核请求 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditTextCheckDTO {

    /**
     * 目标类型：1-帖子 2-活动 3-考试信息 4-评论。
     */
    private Integer targetType;

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
