package cn.jualn.miniapp.module.admin.auth.converter;

import cn.jualn.miniapp.module.admin.auth.bo.qrlogin.*;
import cn.jualn.miniapp.module.admin.auth.enums.AdminQrLoginV2Status;
import cn.jualn.miniapp.module.admin.auth.vo.qrlogin.*;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class AdminQrLoginConverter {
    public AdminQrSessionVO session(AdminQrSessionBO value) {
        boolean confirmed = value.status() == AdminQrLoginV2Status.CONFIRMED
                || value.status() == AdminQrLoginV2Status.CONSUMED;
        return new AdminQrSessionVO(value.sessionId(), value.status(), Instant.ofEpochMilli(value.expiresAt()),
                Math.toIntExact(value.pollIntervalMs()), confirmed ? instant(value.confirmedAt()) : null,
                value.status() == AdminQrLoginV2Status.CONSUMED ? instant(value.consumedAt()) : null);
    }
    public AdminQrCreatedVO created(AdminQrCreatedBO value) {
        return new AdminQrCreatedVO(session(value.session()), value.pollSecret(),
                "/v1/admin/auth/qr-login-sessions/" + value.session().sessionId() + "/code");
    }
    public AdminQrScanVO scan(AdminQrSessionBO value) { return new AdminQrScanVO(session(value), "ADMIN_WEB"); }
    public AdminQrLoginResultVO result(AdminQrLoginResultBO value) {
        var identity = value.profile();
        return new AdminQrLoginResultVO(value.token(), new AdminQrIdentityVO(identity.id(), identity.displayName(),
                identity.avatarText(), identity.roleCode(), identity.roleLabel(), identity.permissions()));
    }
    private Instant instant(Long value) { return value == null ? null : Instant.ofEpochMilli(value); }
}
