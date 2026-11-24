package cn.jualn.miniapp.module.post.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
@RequiredArgsConstructor
public class PostRepresentationTags {
    private final ObjectMapper json;

    public String tag(Object value, long revision) {
        try {
            byte[] representation = json.writeValueAsBytes(value);
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update(Long.toString(revision).getBytes(StandardCharsets.US_ASCII));
            hash.update((byte) 0);
            return "\"" + HexFormat.of().formatHex(hash.digest(representation)) + "\"";
        } catch (java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode post representation", failure);
        }
    }
}
