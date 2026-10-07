package cn.jualn.miniapp.module.admin.auth.vo.qrlogin;

public record AdminQrLoginResultVO(String token, AdminQrIdentityVO profile) {
    @Override public String toString() { return "AdminQrLoginResultVO[credentials redacted]"; }
}
