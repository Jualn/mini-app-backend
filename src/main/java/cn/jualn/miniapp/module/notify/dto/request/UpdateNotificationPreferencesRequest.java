package cn.jualn.miniapp.module.notify.dto.request;

import cn.jualn.miniapp.module.notify.model.NotificationCategory;
import cn.jualn.miniapp.module.notify.model.NotificationChannel;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class UpdateNotificationPreferencesRequest {
    @NotEmpty
    @Size(max = 6)
    @Valid
    private List<@NotNull @Valid Change> changes;
    private final Map<String, Object> unknownProperties = new LinkedHashMap<>();

    @JsonAnySetter
    public void unknown(String name, Object value) {
        unknownProperties.put(name, value);
    }

    @Getter
    @Setter
    public static class Change {
        @NotNull
        private NotificationCategory category;
        @NotNull
        private NotificationChannel channel;
        private Boolean enabled;
        private boolean enabledPresent;
        private final Map<String, Object> unknownProperties = new LinkedHashMap<>();

        @JsonSetter("enabled")
        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
            this.enabledPresent = true;
        }

        @JsonAnySetter
        public void unknown(String name, Object value) {
            unknownProperties.put(name, value);
        }
    }
}
