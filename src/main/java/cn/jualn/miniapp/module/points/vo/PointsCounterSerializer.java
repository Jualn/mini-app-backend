package cn.jualn.miniapp.module.points.vo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;
import java.io.IOException;

/** Numeric Points totals must not inherit the legacy opaque-Long-ID serializer. */
public final class PointsCounterSerializer extends StdScalarSerializer<Long> {
    public PointsCounterSerializer() { super(Long.class); }
    @Override public void serialize(Long value,JsonGenerator generator,SerializerProvider provider) throws IOException { generator.writeNumber(value); }
}
