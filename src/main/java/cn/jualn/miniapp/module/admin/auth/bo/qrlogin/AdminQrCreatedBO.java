package cn.jualn.miniapp.module.admin.auth.bo.qrlogin;

public record AdminQrCreatedBO(AdminQrSessionBO session, String pollSecret) {
    @Override public String toString() { return "AdminQrCreatedBO[credentials redacted]"; }
}
