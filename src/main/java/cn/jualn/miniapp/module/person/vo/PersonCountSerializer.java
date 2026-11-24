package cn.jualn.miniapp.module.person.vo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;

/** Canonical counts are JSON integers; opaque Long identifiers retain the existing string policy. */
public class PersonCountSerializer extends JsonSerializer<Long> {
    @Override public void serialize(Long value,JsonGenerator generator,SerializerProvider provider) throws IOException {
        if(value==null || value<0 || value>9_007_199_254_740_991L) throw cn.jualn.miniapp.module.person.PersonProblems.unavailable();
        generator.writeNumber(value);
    }
}
