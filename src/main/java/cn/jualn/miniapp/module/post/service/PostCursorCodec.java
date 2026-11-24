package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Bounded opaque anchors bind the complete query. They are not authorization tokens.
 */
@Component
public class PostCursorCodec {
    public record Anchor(long id, int order) {
    }

    public String encode(String query, long id, int order) {
        String value = "1:" + fingerprint(query) + ":" + id + ":" + order;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.US_ASCII));
    }

    public Anchor decode(String value, String query) {
        if (value == null) {
            return null;
        }
        if (value.length() > 256 || !value.matches("[A-Za-z0-9_-]+")) {
            throw invalid("INVALID_CURSOR");
        }
        String[] parts;
        long id;
        int order;
        try {
            parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.US_ASCII).split(":", -1);
            if (parts.length != 4 || !"1".equals(parts[0]) || !parts[1].matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
            id = Long.parseLong(parts[2]);
            order = Integer.parseInt(parts[3]);
            if (id <= 0 || order < 0 || !encodeFingerprint(parts[1], id, order).equals(value)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) {
            throw invalid("INVALID_CURSOR");
        }
        if (!fingerprint(query).equals(parts[1])) {
            throw invalid("CURSOR_QUERY_MISMATCH");
        }
        return new Anchor(id, order);
    }

    private String encodeFingerprint(String hash, long id, int order) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("1:" + hash + ":" + id + ":" + order).getBytes(StandardCharsets.US_ASCII));
    }

    private String fingerprint(String query) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private ContractProblemException invalid(String code) {
        return ContractProblemException.validation(new ContractProblemException.Violation("query", "cursor", code, "Invalid cursor for this query"));
    }
}
