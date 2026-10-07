package cn.jualn.miniapp.module.exam.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.exam.bo.ExamPageBO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Opaque PublicEvent cursor bound to the filters and sort used to create it. */
public final class PublicEventCursorCodec {
    private static final String VERSION = "v1";
    private static final String SORT = "-publishedAt";
    private static final String INVALID = "分页游标无效或与过滤、排序条件不匹配";

    private PublicEventCursorCodec() {}

    public static String encode(ExamPageBO query, Long id) {
        if (id == null) return null;
        String raw = String.join("|", VERSION, id.toString(), fingerprint(query));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Long decode(ExamPageBO query) {
        String encoded = query.getCursor();
        if (encoded == null) return null;
        if (encoded.isBlank()) throw invalidCursor();
        try {
            String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            long id = parts.length == 3 ? Long.parseLong(parts[1]) : -1;
            if (parts.length != 3 || !VERSION.equals(parts[0]) || id <= 0
                    || !fingerprint(query).equals(parts[2])) throw new IllegalArgumentException("cursor mismatch");
            return id;
        } catch (IllegalArgumentException exception) { throw invalidCursor(); }
    }

    private static String fingerprint(ExamPageBO query) {
        String canonical = String.join("\n", SORT, value(query.getKeyword()),
                value(query.getEventType()), value(query.getLifecycleStatus()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }

    private static String value(Object value) { return value == null ? "" : value.toString(); }
    private static ContractProblemException invalidCursor() {
        return new ContractProblemException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "/problems/invalid-cursor", INVALID);
    }
}
