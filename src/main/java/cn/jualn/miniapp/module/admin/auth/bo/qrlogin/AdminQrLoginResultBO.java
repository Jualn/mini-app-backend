package cn.jualn.miniapp.module.admin.auth.bo.qrlogin;

public record AdminQrLoginResultBO(String token, AdminQrIdentityBO profile, long tokenExpiresAt) {
    @Override public String toString() { return "AdminQrLoginResultBO[credentials redacted]"; }
}
