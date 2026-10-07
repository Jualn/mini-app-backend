package cn.jualn.miniapp.module.eventcontent.service;

import cn.jualn.miniapp.module.eventcontent.bo.EventContactBO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Decode stored contacts without losing canonical keys or disguising corrupt data as absence. */
@Component
@RequiredArgsConstructor
public class EventContactCodec {
    private final ObjectMapper objectMapper;

    public List<EventContactBO> read(String contactsJson, String legacyJson, String legacyName, String legacyPhone) {
        if (contactsJson != null && !contactsJson.isBlank()) {
            JsonNode contacts = parse(contactsJson);
            if (!contacts.isArray()) {
                throw new IllegalStateException("Stored contacts must be an array");
            }
            if (!contacts.isEmpty()) {
                var result = new ArrayList<EventContactBO>();
                var keys = new HashSet<String>();
                for (JsonNode contact : contacts) {
                    String key = required(contact, "contactKey");
                    if (!keys.add(key)) {
                        throw new IllegalStateException("Duplicate stored contact key");
                    }
                    result.add(new EventContactBO(key, required(contact, "name"),
                            required(contact, "contact"), contact.hasNonNull("remark") ? contact.get("remark").asText() : null));
                }
                return List.copyOf(result);
            }
        }
        // Legacy storage is one name/phone object, not the canonical contacts array.
        if (legacyJson != null && !legacyJson.isBlank()) {
            JsonNode contact = parse(legacyJson);
            if (!contact.isObject()) {
                throw new IllegalStateException("Legacy contact must be an object");
            }
            legacyName = contact.path("name").asText(null);
            legacyPhone = contact.path("phone").asText(null);
        }
        if (legacyName == null && legacyPhone == null) {
            return List.of();
        }
//        if (legacyPhone == null || legacyPhone.isBlank()) {
//            throw new IllegalStateException("Legacy contact has no contact value");
//        }
        return List.of(new EventContactBO("primary",
                legacyName == null || legacyName.isBlank() ? "联系人" : legacyName, legacyPhone, null));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored contact JSON", exception);
        }
    }

    private String required(JsonNode value, String name) {
        JsonNode field = value.get(name);
        if (field == null || !field.isTextual() || field.asText().isBlank()) {
            throw new IllegalStateException("Stored contact is missing " + name);
        }
        return field.asText();
    }
}
