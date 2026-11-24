package cn.jualn.miniapp.module.learningtask.vo.admin;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;

import java.io.IOException;

/**
 * Local numeric counters override the legacy global long-as-ID serializer.
 */
public final class LearningTaskCounterSerializer extends StdScalarSerializer<Long> {
    public LearningTaskCounterSerializer() {
        super(Long.class);
    }

    @Override
    public void serialize(Long value, JsonGenerator generator, SerializerProvider provider) throws IOException {
        generator.writeNumber(value);
    }
}
