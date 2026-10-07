package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.notify.dto.request.CanonicalNotificationQuery;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;

public final class NotificationCursorCodec {
    private static final String VERSION = "v1";
    private static final String INVALID = "分页游标无效或与当前身份、过滤条件不匹配";

    private NotificationCursorCodec() {}

    public static String encode(long userId, CanonicalNotificationQuery query,
                                LocalDateTime createdAt, long id) {
        if (createdAt == null || id <= 0) return null;
        String raw = String.join("|", VERSION, createdAt.toString(), Long.toString(id),
                fingerprint(userId, query));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Position decode(long userId, CanonicalNotificationQuery query) {
        if (query.getCursor() == null) return null;
        if (query.getCursor().isBlank()) throw invalid();
        try {
            String raw = new String(Base64.getUrlDecoder().decode(query.getCursor()), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])
                    || !fingerprint(userId, query).equals(parts[3])) throw new IllegalArgumentException();
            long id = Long.parseLong(parts[2]);
            if (id <= 0) throw new IllegalArgumentException();
            return new Position(LocalDateTime.parse(parts[1]), id);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static String fingerprint(long userId, CanonicalNotificationQuery query) {
        String canonical = String.join("\n", Long.toString(userId),
                query.getCategory() == null ? "" : query.getCategory().name(),
                query.getIsRead() == null ? "" : query.getIsRead().toString(),
                "-createdAt,-notificationId");
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ContractProblemException invalid() {
        return new ContractProblemException(HttpStatus.BAD_REQUEST, "/problems/invalid-cursor", INVALID);
    }

    public record Position(LocalDateTime createdAt, long id) {}
}
