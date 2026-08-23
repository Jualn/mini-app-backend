package cn.jualn.miniapp.module.admin.auth.support;

import cn.jualn.miniapp.common.exception.SystemException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 管理端认证随机值与摘要工具。
 */
public final class AdminAuthCrypto {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AdminAuthCrypto() {
    }

    public static String randomUrlToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new SystemException("当前运行环境不支持 SHA-256", e);
        }
    }
}
