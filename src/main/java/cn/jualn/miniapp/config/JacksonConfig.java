package cn.jualn.miniapp.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ser.std.EnumSerializer;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 全局序列化配置
 * <p>
 * 解决以下问题：
 * 1. Long 类型超出 JS 精度范围（前端 id 丢失精度）→ Long 转 String
 * 2. LocalDateTime 序列化为时间戳而非字符串 → 统一格式 yyyy-MM-dd HH:mm:ss
 * 3. null 字段照常返回（不过滤），保持接口字段稳定
 * 4. 前端传来未知字段不报错（小程序版本兼容）
 * 5. 枚举只接受字符串枚举名，不接受数字 ordinal，避免前后端枚举错位
 */
@Configuration
public class JacksonConfig {

    private static final String DATE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_FORMAT       = "yyyy-MM-dd";

    @Bean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        JavaTimeModule javaTimeModule = new JavaTimeModule();

        // LocalDateTime 序列化/反序列化格式
        javaTimeModule.addSerializer(LocalDateTime.class,
            new LocalDateTimeSerializer(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT)));
        javaTimeModule.addDeserializer(LocalDateTime.class,
            new LocalDateTimeDeserializer(DateTimeFormatter.ofPattern(DATE_TIME_FORMAT)));

        // LocalDate 序列化/反序列化格式
        javaTimeModule.addSerializer(LocalDate.class,
            new LocalDateSerializer(DateTimeFormatter.ofPattern(DATE_FORMAT)));
        javaTimeModule.addDeserializer(LocalDate.class,
            new LocalDateDeserializer(DateTimeFormatter.ofPattern(DATE_FORMAT)));

        return builder
            .modules(javaTimeModule)
            // Long/long → String，解决前端 JS 精度丢失问题
            .serializerByType(Long.class, ToStringSerializer.instance)
            .serializerByType(long.class, ToStringSerializer.instance)
            // 不把时间序列化为时间戳
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            // 前端传来多余字段不报错（兼容小程序多版本并行）
            .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // 枚举入参必须使用字符串枚举名，如 POST / ACTIVITY，禁止传 0 / 1 这类 ordinal
            .featuresToEnable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            // null 字段正常返回，不过滤
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .build();
    }
}
