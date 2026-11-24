package cn.jualn.miniapp.module.notify.payload.data;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import java.io.IOException;
import java.util.Map;
/** Stable logical IDs for new content; only the five historical Java IDs remain accepted. */
public final class NoticeDataTypeIds extends TypeIdResolverBase {
    private static final Map<Class<?>,String> IDS=Map.of(
        CommentNoticeData.class,"COMMENT",ReplyNoticeData.class,"REPLY",
        AuditResultNoticeData.class,"AUDIT_RESULT",ActivityRemindNoticeData.class,"ACTIVITY_REMINDER",
        BroadcastNoticeData.class,"BROADCAST");
    private JavaType base;
    @Override public void init(JavaType type) {base=type;}
    @Override public JsonTypeInfo.Id getMechanism() {return JsonTypeInfo.Id.CUSTOM;}
    @Override public String idFromValue(Object value) {return idFromValueAndType(value,value.getClass());}
    @Override public String idFromValueAndType(Object value,Class<?> type) {
        var id=IDS.get(type);if(id==null) throw new IllegalArgumentException("Unsupported notification content type");return id;
    }
    @Override public JavaType typeFromId(DatabindContext context,String id) throws IOException {
        for(var entry:IDS.entrySet()) {
            String legacy="cn.jualn.miniapp.module.wx.notice.data."+entry.getKey().getSimpleName();
            if(entry.getValue().equals(id) || legacy.equals(id)) return context.constructSpecializedType(base,entry.getKey());
        }
        throw new IOException("Unsupported notification content type");
    }
}
