package cn.jualn.miniapp.module.notify.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
@Setter
public class MarkNotificationReadRequest {
    @NotNull
    @AssertTrue
    private Boolean isRead;
    private final Map<String, Object> unknownProperties = new LinkedHashMap<>();

    @JsonAnySetter
    public void unknown(String name, Object value) {
        unknownProperties.put(name, value);
    }
}
