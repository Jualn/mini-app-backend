package cn.jualn.miniapp.module.admin.auth.bo.qrlogin;

public record AdminQrLoginResultBO(String token, AdminQrIdentityBO profile) {
    @Override public String toString() { return "AdminQrLoginResultBO[credentials redacted]"; }
}
