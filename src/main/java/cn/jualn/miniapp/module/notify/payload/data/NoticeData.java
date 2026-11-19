package cn.jualn.miniapp.module.notify.payload.data;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import java.util.Map;
/** Business content facts; legacy stored type IDs are decoded by an explicit allowlist. */
@JsonTypeInfo(use=JsonTypeInfo.Id.CUSTOM,include=JsonTypeInfo.As.PROPERTY,property="@class")
@JsonTypeIdResolver(NoticeDataTypeIds.class)
@FunctionalInterface
public interface NoticeData { Map<String,Object> toMap(); }
