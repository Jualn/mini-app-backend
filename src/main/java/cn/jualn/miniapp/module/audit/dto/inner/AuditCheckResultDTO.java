package cn.jualn.miniapp.module.audit.dto.inner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审核结果 DTO。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditCheckResultDTO {

    /**
     * 是否通过审核。
     */
    private Boolean passed;

    /**
     * 是否异步处理中。
     */
    private Boolean pending;

    /**
     * 微信 traceId。
     */
    private String traceId;

    /**
     * 微信建议：pass/risky/reject。
     */
    private String suggest;

    /**
     * 微信风险标签。
     */
    private Integer label;
}
