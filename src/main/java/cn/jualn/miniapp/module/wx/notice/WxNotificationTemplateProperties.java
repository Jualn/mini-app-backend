package cn.jualn.miniapp.module.wx.notice;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Deployment configuration only. No credentials, dynamic expressions or hot reload. */
@Data
@Component
@ConfigurationProperties(prefix = "wx.notification")
public class WxNotificationTemplateProperties {
    private String environment = "local";
    private Channel miniProgram = new Channel();
    private Channel officialAccount = new Channel();
    private Map<String, Definition> definitions = new LinkedHashMap<>();
    private Map<String, Binding> bindings = new LinkedHashMap<>();

    @Data public static class Channel {
        private boolean enabled;
    }

    @Data public static class Definition {
        private String channel;
        private String accountRef;
        private String product;
        private String templateId;
        private int version = 1;
        private boolean enabled;
        /** Human-reviewed evidence reference; never means per-user permission. */
        private String evidenceRef;
        private String miniProgramState = "formal";
        private Map<String, FieldRule> fields = new LinkedHashMap<>();
    }
    @Data public static class FieldRule {
        private String dataType = "TEXT";
        private boolean required;
        private Integer maxLength;
        private List<String> allowedValues = List.of();
    }
    @Data public static class Binding {
        private String notificationType;
        private String channel;
        private String accountRef;
        private String definitionRef;
        private int version = 1;
        private boolean enabled;
        private boolean subscribeVisible = true;
        private String displayName;
        private String jumpTarget;
        private Map<String, Mapping> fields = new LinkedHashMap<>();
    }
    @Data public static class Mapping {
        private String source;
        private String formatter = "text";
        private String zone = "Asia/Shanghai";
        private String overflow = "reject";
        private String defaultValue;
    }
}
