package cn.jualn.miniapp.third.cos.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Test utilities for CosClient unit tests.
 */
public class CosClientTestUtils {

    public static cn.jualn.miniapp.third.cos.dto.CosTemporaryCredentialDTO parseTemporaryCredential(String responseBody) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(responseBody);
        JsonNode creds = root.path("Response").path("Credentials");

        String tmpSecretId = creds.path("TmpSecretId").asText(null);
        String tmpSecretKey = creds.path("TmpSecretKey").asText(null);
        String token = creds.path("Token").asText(null);
        long expiredTime = creds.path("ExpiredTime").asLong(0L);

        java.time.LocalDateTime expireAt = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(expiredTime), java.time.ZoneId.systemDefault());

        return cn.jualn.miniapp.third.cos.dto.CosTemporaryCredentialDTO.builder()
                .tmpSecretId(tmpSecretId)
                .tmpSecretKey(tmpSecretKey)
                .sessionToken(token)
                .expiredTime(expiredTime)
                .expireAt(expireAt)
                .build();
    }

    public static String buildCanonicalRequest(String body) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("POST\n/\n\n");
        sb.append("content-type:application/json; charset=utf-8\n");
        sb.append("host:sts.tencentcloudapi.com\n\n");
        sb.append("content-type;host\n");

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) hex.append('0');
            hex.append(h);
        }
        sb.append(hex);
        return sb.toString();
    }
}

