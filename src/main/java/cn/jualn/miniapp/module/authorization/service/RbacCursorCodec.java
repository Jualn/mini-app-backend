package cn.jualn.miniapp.module.authorization.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.jualn.miniapp.module.admin.auth.support.AdminAuthCrypto;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Local continuation integrity, never an authorization credential. Restart/another node requires a fresh first page.
 */
@Component
public class RbacCursorCodec {
    public record Anchor(int version, String kind, long actor, String binding, String evaluatedAt,
                         String highTime, String highId, long highOrder, String lastTime, String lastId) {
    }

    private final byte[] key = AdminAuthCrypto.randomUrlToken(32).getBytes(StandardCharsets.US_ASCII);
    private final ObjectMapper json = new ObjectMapper();

    public String encode(Anchor anchor) {
        try {
            String body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(anchor));
            return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Anchor decode(String value) {
        try {
            if (value.length() > 8192 || !value.matches("[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            String[] parts = value.split("[.]", -1);
            if (!MessageDigest.isEqual(sign(parts[0]), Base64.getUrlDecoder().decode(parts[1])))
                throw new IllegalArgumentException();
            Anchor anchor = json.readValue(Base64.getUrlDecoder().decode(parts[0]), Anchor.class);
            if (anchor.version() != 1 || anchor.evaluatedAt() == null || anchor.lastId() == null || anchor.lastTime() == null)
                throw new IllegalArgumentException();
            return anchor;
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw invalid("CURSOR_STALE");
        }
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static ContractProblemException invalid(String code) {
        return ContractProblemException.validation(
                new ContractProblemException.Violation("query", "/cursor", code, "Refresh from the first page"));
    }
}
