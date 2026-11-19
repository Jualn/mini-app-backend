package cn.jualn.miniapp.module.learningtask.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** Preserve microseconds in durable replay snapshots; accept the previous global second-only format. */
public final class LearningTaskStorageJson {
    private LearningTaskStorageJson() {}

    public static ObjectMapper copyOf(ObjectMapper applicationMapper) {
        var module = new SimpleModule("learning-task-storage-time");
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(DateTimeFormatter.ISO_LOCAL_DATE_TIME) {
            @Override public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                if (parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_STRING)) {
                    String value = parser.getText();
                    try {
                        return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    } catch (DateTimeParseException previousFormat) {
                        return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    }
                }
                return super.deserialize(parser, context);
            }
        });
        return applicationMapper.copy().registerModule(module)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
