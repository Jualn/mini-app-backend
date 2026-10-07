package cn.jualn.miniapp.module.admin.auth.vo.qrlogin;

public record AdminQrCreatedVO(AdminQrSessionVO session, String pollSecret, String imageUrl) {
    @Override public String toString() { return "AdminQrCreatedVO[credentials redacted]"; }
}
