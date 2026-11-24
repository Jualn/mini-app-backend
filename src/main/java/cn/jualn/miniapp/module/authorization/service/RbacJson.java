package cn.jualn.miniapp.module.authorization.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Internal v1 snapshots/fingerprints must retain microseconds independently of legacy HTTP formatting.
 */
final class RbacJson {
    private RbacJson() {
    }

    static ObjectMapper mapper() {
        var time = new JavaTimeModule();
        time.addDeserializer(LocalDateTime.class, new JsonDeserializer<>() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext c) throws IOException {
                String value = p.getText();
                try {
                    return LocalDateTime.parse(value);
                } catch (DateTimeParseException oldFormat) {
                    // Existing v1 internal snapshots used the application's legacy seconds representation.
                    return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                }
            }
        });
        return new ObjectMapper().registerModule(time).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    static ObjectMapper legacyMapper() {
        var legacy = new SimpleModule();
        legacy.addSerializer(Long.class, ToStringSerializer.instance);
        legacy.addSerializer(Long.TYPE, ToStringSerializer.instance);
        legacy.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return mapper().registerModule(legacy);
    }
}
