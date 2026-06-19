package cn.jualn.miniapp.module.audit.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审核最终结论状态。
 *
 * <p>表示审核任务从提交到完成的全生命周期状态，支持多媒体异步审核的挂起状态。</p>
 */
@Getter
@AllArgsConstructor
public enum AuditStatus {
    /**
     * 待审核（初始状态）。仅用于多媒体异步审核任务。
     */
    PENDING(0, "待审核"),
    /**
     * 审核通过。
     */
    PASS(1, "通过"),
    /**
     * 审核拒绝。
     */
    REJECT(2, "拒绝");

    private final int code;
    private final String desc;
}
