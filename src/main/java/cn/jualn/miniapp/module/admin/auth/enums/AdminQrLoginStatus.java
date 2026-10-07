package cn.jualn.miniapp.module.admin.auth.enums;

/**
 * 管理端二维码登录会话状态。
 */
public enum AdminQrLoginStatus {
    PENDING,
    SCANNED,
    CONFIRMED,
    DENIED,
    EXPIRED
}
