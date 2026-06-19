package cn.jualn.miniapp.module.audit.bo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审核结果业务对象。
 *
 * <p>文本审核和多媒体审核的结果都通过此对象返回，支持同步和异步两种模式。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditCheckResultBO {

    /**
     * 审核是否通过。true=通过，false=拒绝，null=异步处理中。
     */
    private Boolean passed;

    /**
     * 审核是否处于异步等待中。true 表示已提交异步审核任务，结果待微信回调；false 或 null 表示已完成同步审核。
     */
    private Boolean pending;

    /**
     * 微信审核任务的 traceId，用于回调关联和审核日志追踪。
     */
    private String traceId;

    /**
     * 微信审核建议值：pass（通过）、risky（风险）、reject（拒绝）等。
     */
    private String suggest;

    /**
     * 微信审核风险标签代码，标识内容违规类型（色情、政治、垃圾等）。
     */
    private Integer label;


}
