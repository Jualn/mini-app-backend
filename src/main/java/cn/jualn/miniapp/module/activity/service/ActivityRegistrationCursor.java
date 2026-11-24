package cn.jualn.miniapp.module.activity.service;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.activity.entity.ActivityRegistration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

public final class ActivityRegistrationCursor {
    private ActivityRegistrationCursor() { }
    public record Seek(LocalDateTime submittedAt, long id) { }
    public static String encode(long user, Integer status, ActivityRegistration row) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("v1|" + user + "|" + status + "|" + row.getSubmittedAt() + "|" + row.getId()).getBytes(StandardCharsets.UTF_8));
    }
    public static Seek decode(String cursor, long user, Integer status) {
        if (cursor == null) return null;
        try {
            var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 5 || !"v1".equals(parts[0]) || !Long.toString(user).equals(parts[1])
                    || !String.valueOf(status).equals(parts[2])) throw new IllegalArgumentException();
            long id = Long.parseLong(parts[4]);
            if (id <= 0) throw new IllegalArgumentException();
            return new Seek(LocalDateTime.parse(parts[3]), id);
        } catch (RuntimeException invalid) {
            throw new ContractProblemException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "/problems/invalid-cursor", "Invalid personal activity registration cursor");
        }
    }
}
