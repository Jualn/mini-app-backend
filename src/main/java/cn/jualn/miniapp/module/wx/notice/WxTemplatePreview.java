package cn.jualn.miniapp.module.wx.notice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.Path;
import java.util.Map;

/** Offline CLI only. Does not create a Spring context, WxClient, UserService or AccessToken. */
public final class WxTemplatePreview {
    private WxTemplatePreview() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=3) throw new IllegalArgumentException("Usage: WxTemplatePreview config.yaml mock-notification.json binding-ref");
        var environment=new StandardEnvironment();
        // Do not consume environment/system credentials or application profile overrides.
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        new YamlPropertySourceLoader().load("offline",new FileSystemResource(args[0]))
                .forEach(s->environment.getPropertySources().addLast(s));
        var binder=Binder.get(environment);
        var definitions=binder.bind("wx.notification",Bindable.of(WxNotificationTemplateProperties.class))
                .orElseGet(WxNotificationTemplateProperties::new);
        var json=new ObjectMapper();
        Map<String,Object> frozen=json.readValue(Path.of(args[1]).toFile(),new TypeReference<>(){});
        var registry=new WxNotificationTemplateRegistry(definitions,null);
        var preview=new WxNotificationTemplateRenderer().preview(registry,args[2],frozen);
        // Native Windows stdout may default to a legacy charset; preserve preview Unicode explicitly.
        System.out.write(json.writerWithDefaultPrettyPrinter().writeValueAsString(preview)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.write('\n');
        System.out.flush();
        if(!preview.valid()) throw new IllegalArgumentException("Offline template preview failed validation");
    }
}
