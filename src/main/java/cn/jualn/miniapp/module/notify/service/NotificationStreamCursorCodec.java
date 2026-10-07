package cn.jualn.miniapp.module.notify.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.notify.bo.NotificationCenterBO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/** Authenticated, opaque head/page tokens. A head never carries category filters. */
@Component
public class NotificationStreamCursorCodec {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TTL_SECONDS = 86_400;
    private final SecretKeySpec key;

    public NotificationStreamCursorCodec(@Value("${notification.cursor-secret:}") String secret) {
        try {
            byte[] material;
            if (secret == null || secret.isBlank()) {
                material = new byte[32];
                RANDOM.nextBytes(material);
            } else {
                if (secret.length() < 32) {
                    throw new IllegalArgumentException("notification.cursor-secret requires at least 32 characters");
                }
                material = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            }
            key = new SecretKeySpec(material, "AES");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String head(long userId, long boundary) { return encode(userId, "head", boundary, ""); }
    public long decodeHead(long userId, String token) { return decode(userId, token, "head", ""); }
    public String page(long userId, long before, NotificationCenterBO.Query query) {
        return encode(userId, "page", before, filters(query));
    }
    public Long decodePage(long userId, NotificationCenterBO.Query query) {
        return query.cursor() == null ? null : decode(userId, query.cursor(), "page", filters(query));
    }
    private String filters(NotificationCenterBO.Query query) {
        return "structured," + query.category() + "," + query.boxCategory() + "," + query.isRead();
    }
    private String encode(long userId, String purpose, long boundary, String filters) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            String raw = "1|" + userId + "|" + purpose + "|" + boundary + "|"
                    + (Instant.now().getEpochSecond() + TTL_SECONDS) + "|" + filters;
            byte[] encrypted = cipher.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Notification cursor encryption unavailable", failure);
        }
    }
    private long decode(long userId, String token, String purpose, String filters) {
        try {
            if (token == null || token.isBlank() || token.length() > 2048) { throw invalid(); }
            byte[] bytes = Base64.getUrlDecoder().decode(token);
            if (bytes.length < 29) { throw invalid(); }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 0, 12));
            String[] parts = new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 6 || !"1".equals(parts[0]) || userId != Long.parseLong(parts[1])
                    || !purpose.equals(parts[2]) || !filters.equals(parts[5])
                    || Instant.now().getEpochSecond() >= Long.parseLong(parts[4])) { throw invalid(); }
            long boundary = Long.parseLong(parts[3]);
            if (boundary < 0) { throw invalid(); }
            return boundary;
        } catch (java.security.GeneralSecurityException | IllegalArgumentException malformed) {
            throw invalid();
        }
    }
    public static ContractProblemException invalid() {
        return new ContractProblemException(HttpStatus.BAD_REQUEST, "/problems/invalid-cursor", "通知游标无效或已过期");
    }
}
