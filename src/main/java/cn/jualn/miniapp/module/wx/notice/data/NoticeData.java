package cn.jualn.miniapp.module.wx.notice.data;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

// 接口跟抽象类不能反序列化，所以配置这个用于反序列化让它的值变成对应实现的包 "@class":"cn.jualn.xxx.CommentNoticeData"
// 用于反序列化
@JsonTypeInfo(
        use = JsonTypeInfo.Id.CLASS,
        include = JsonTypeInfo.As.PROPERTY,
        property = "@class"
)
@FunctionalInterface
public interface NoticeData {
    Map<String, Object> toMap();
}
