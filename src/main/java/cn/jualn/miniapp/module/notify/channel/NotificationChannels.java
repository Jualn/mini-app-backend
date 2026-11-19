package cn.jualn.miniapp.module.notify.channel;

import cn.jualn.miniapp.module.notify.enums.NotifyType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class NotificationChannels {
    private final Map<String,NotificationChannelAdapter> adapters;
    public NotificationChannels(List<NotificationChannelAdapter> implementations) {
        var values=new HashMap<String,NotificationChannelAdapter>();
        for(var adapter:implementations) {
            var key=adapter.channel();
            if(key==null || key.isBlank() || values.putIfAbsent(key,adapter)!=null)
                throw new IllegalStateException("Notification channel must have one explicit adapter");
        }
        adapters=Map.copyOf(values);
    }
    public NotificationChannelAdapter find(String channel) {return channel==null?null:adapters.get(channel);}
    public boolean available(String channel,NotifyType type) {
        var adapter=find(channel);return adapter!=null && adapter.isDeliveryAvailable(type);
    }
    public boolean configured(String channel,NotifyType type) {
        var adapter=find(channel);return adapter!=null && adapter.isTemplateConfigured(type);
    }
}
