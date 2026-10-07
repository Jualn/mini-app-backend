package cn.jualn.miniapp.module.admin.auth.bo.qrlogin;

public record AdminQrCandidateBO(String token, String tokenHash, String profileJson, long tokenExpiresAt) {
    @Override public String toString() { return "AdminQrCandidateBO[credentials redacted]"; }
}
