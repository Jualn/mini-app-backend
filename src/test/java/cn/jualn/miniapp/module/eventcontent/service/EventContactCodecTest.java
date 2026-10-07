package cn.jualn.miniapp.module.eventcontent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventContactCodecTest {
    private final EventContactCodec codec = new EventContactCodec(new ObjectMapper());

    @Test
    void preservesKeysCollectionAndRemarks() {
        var contacts = codec.read("""
                [{"contactKey":"second","name":"Two","contact":"222","remark":"Evenings"},
                 {"contactKey":"first","name":"One","contact":"111"}]
                """, null, null, null);
        assertEquals(2, contacts.size());
        assertEquals("second", contacts.get(0).contactKey());
        assertEquals("Evenings", contacts.get(0).remark());
        assertEquals("first", contacts.get(1).contactKey());
    }

    @Test
    void legacySingletonHasStableKeyAndMalformedDataIsNotAbsence() {
        assertEquals("primary", codec.read("[]", "{\"name\":\"Office\",\"phone\":\"123\"}", null, null)
                .get(0).contactKey());
        assertThrows(IllegalStateException.class, () -> codec.read("{broken", null, null, null));
        assertThrows(IllegalStateException.class, () -> codec.read("[{\"name\":\"No key\"}]", null, null, null));
        assertThrows(IllegalStateException.class, () -> codec.read(null, "[]", null, null));
    }
}
