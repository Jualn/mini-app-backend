package cn.jualn.miniapp.third.wx.crypto;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 提供普通明文模式的微信签名验证功能。
 * 微信服务器会在发送请求时附带 signature、timestamp 和 nonce 参数，开发者
 * 需要使用预设的 token、timestamp 和 nonce 进行 SHA-1 加密，并与 signature 进行比较，以验证请求的合法性。
 */
@Service
public class WxSignatureService {

    public boolean verify(String token, String signature, String timestamp, String nonce) {
        if (!StringUtils.hasText(token)
                || !StringUtils.hasText(signature)
                || !StringUtils.hasText(timestamp)
                || !StringUtils.hasText(nonce)) {
            return false;
        }

        String[] array = {token, timestamp, nonce};
        Arrays.sort(array);

        String raw = String.join("", array);
        return signature.equals(sha1(raw));
    }

    private String sha1(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                String s = Integer.toHexString(b & 0xff);
                if (s.length() == 1) {
                    hex.append('0');
                }
                hex.append(s);
            }

            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("微信 signature 计算失败", e);
        }
    }}
