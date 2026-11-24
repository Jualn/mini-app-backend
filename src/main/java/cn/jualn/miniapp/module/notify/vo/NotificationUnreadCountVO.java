package cn.jualn.miniapp.module.notify.vo;

public record NotificationUnreadCountVO(
        @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=NotificationUnreadCountVO.CountSerializer.class)
        long unreadCount) {
    /** Counts are Contract integers; the global Long serializer is reserved for identity compatibility. */
    public static final class CountSerializer extends com.fasterxml.jackson.databind.ser.std.NumberSerializer {
        public CountSerializer() { super(Long.class); }
    }
}
