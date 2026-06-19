package cn.jualn.miniapp.module.audit.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审核来源类型。
 *
 * <p>标识审核任务的来源：自动化审核或人工审核。</p>
 */
@Getter
@AllArgsConstructor
public enum AuditSourceEnum {
    /**
     * 微信安全 API 自动审核（包括文本同步和多媒体异步）。
     */
    WX_AUTO(1, "微信安全API自动"),
    /**
     * 管理员人工审核。
     */
    ADMIN_MANUAL(2, "管理员人工");

    private final int code;
    private final String desc;
}
