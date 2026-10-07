package cn.jualn.miniapp.module.admin.auth.enums;

public enum AdminQrLoginV2Status {
    PENDING, SCANNED, CONFIRMED, CONSUMED, EXPIRED, REJECTED, CANCELLED;

    public boolean terminal() {
        return this == CONSUMED || this == EXPIRED || this == REJECTED || this == CANCELLED;
    }
}
