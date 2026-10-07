package cn.jualn.miniapp.module.notify.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
public class ReadNotificationsThroughRequest {
    @NotBlank private String throughCursor;
    private final Map<String, Object> unknownProperties = new HashMap<>();
    @JsonAnySetter public void unknown(String name, Object value) { unknownProperties.put(name, value); }
}
