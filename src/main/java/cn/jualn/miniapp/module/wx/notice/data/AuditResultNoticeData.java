package cn.jualn.miniapp.module.wx.notice.data;

import cn.jualn.miniapp.module.wx.constant.WxNoticeKeys;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

public record AuditResultNoticeData(
        String auditContent,
        String auditResult,
        String auditReason,
        LocalDateTime auditTime
) implements NoticeData {

    /**
     * 审核结果通知数据载荷。
     * 对应 NotifyType.AUDIT_RESULT
     *
     * @param auditContent 审核内容摘要 → YAML source: auditContent
     * @param auditResult  审核结果 → YAML source: auditResult（有 default-value，如 "拒绝"）
     * @param auditReason  审核拒绝原因
     * @param auditTime  审核时间 → YAML source: auditTime
     */
    public AuditResultNoticeData {
        Objects.requireNonNull(auditTime, "auditTime must not be null");
    }

    @Override
    public Map<String, Object> toMap() {
        return Map.of(
                WxNoticeKeys.AUDIT_CONTENT, auditContent != null ? auditContent : "",
                WxNoticeKeys.AUDIT_RESULT, auditResult != null ? auditResult : "",
                WxNoticeKeys.AUDIT_REASON, auditReason != null ? auditReason : "",
                WxNoticeKeys.AUDIT_TIME, auditTime
        );
    }
}
