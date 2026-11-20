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
    @Size(max = 27)
    @Valid
    private List<@NotNull @Valid Change> changes;
    @Getter(lombok.AccessLevel.NONE)
    @Setter(lombok.AccessLevel.NONE)
    private final Map<String, Object> unknownProperties = new LinkedHashMap<>();

    public Map<String, Object> unknownProperties() { return unknownProperties; }

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
        @Getter(lombok.AccessLevel.NONE)
        @Setter(lombok.AccessLevel.NONE)
        private boolean enabledPresent;

        public boolean enabledPresent() { return enabledPresent; }
        @Getter(lombok.AccessLevel.NONE)
        @Setter(lombok.AccessLevel.NONE)
        private final Map<String, Object> unknownProperties = new LinkedHashMap<>();

        public Map<String, Object> unknownProperties() { return unknownProperties; }

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
