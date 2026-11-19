package cn.jualn.miniapp.module.person.service.support;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.HttpStatus;

public final class PersonnelValues {
    private PersonnelValues() {
    }

    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public static ContractProblemException problem(HttpStatus status, String type) {
        return new ContractProblemException(status, "/problems/" + type, "Personnel operation cannot be completed");
    }

    public static ContractProblemException unavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "people-source-unavailable");
    }

    public static ContractProblemException conflict() {
        return problem(HttpStatus.CONFLICT, "roster-state-conflict");
    }

    public static ContractProblemException invalid(String field) {
        return ContractProblemException.validation(new ContractProblemException.Violation("body", "/" + field, "INVALID_VALUE", "Invalid personnel value"));
    }

    public static String text(String s, int max, String field) {
        if (s == null || s.strip().isBlank() || s.codePointCount(0, s.length()) > max) throw invalid(field);
        return s.strip();
    }

    public static void reason(String reason, String reference) {
        text(reason, 2000, "reason");
        text(reference, 256, "verificationReference");
    }

    public static void key(String key) {
        text(key, 128, "Idempotency-Key");
        if (key.codePoints().anyMatch(Character::isISOControl)) throw invalid("Idempotency-Key");
    }

    public static void page(int p, int s) {
        if (p < 1 || s < 1 || s > 100) throw invalid("page");
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public static Instant instant(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC);
    }

    public static LocalDateTime utc(Instant t) {
        return t == null ? null : LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    public static com.fasterxml.jackson.databind.JsonNode tree(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception e) {
            throw unavailable();
        }
    }

    public static String encode(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw unavailable();
        }
    }

    public static <T> T decode(String s, Class<T> t) {
        try {
            return JSON.readValue(s, t);
        } catch (Exception e) {
            throw unavailable();
        }
    }

    public static <T> T decode(String s, TypeReference<T> t) {
        try {
            return JSON.readValue(s, t);
        } catch (Exception e) {
            throw unavailable();
        }
    }

    public static void one(int count) {
        if (count != 1) throw unavailable();
    }

    public static String etag(long version) {
        return "\"" + version + "\"";
    }

    public static void match(String supplied, long version) {
        if (supplied == null) throw ContractProblemException.preconditionRequired();
        if (!supplied.matches("\"[^\"\\r\\n]+\"")) throw invalid("If-Match");
        if (!etag(version).equals(supplied)) throw ContractProblemException.preconditionFailed();
    }

    public static String mask(String s) {
        return s == null || s.isBlank() ? null : "****" + s.substring(Math.max(0, s.length() - 2));
    }
}
