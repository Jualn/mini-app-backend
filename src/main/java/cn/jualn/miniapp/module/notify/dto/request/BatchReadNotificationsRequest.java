package cn.jualn.miniapp.module.notify.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class BatchReadNotificationsRequest {
    @NotEmpty @Size(max = 50)
    private List<@NotBlank @Size(max = 128) String> notificationIds;
    private final Map<String, Object> unknownProperties = new HashMap<>();
    @JsonAnySetter public void unknown(String name, Object value) { unknownProperties.put(name, value); }
}
