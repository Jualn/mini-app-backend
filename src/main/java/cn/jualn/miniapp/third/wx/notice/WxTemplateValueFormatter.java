package cn.jualn.miniapp.third.wx.notice;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Pure value conversion. No notification types, user data lookup, routes or provider request DTOs. */
public final class WxTemplateValueFormatter {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private WxTemplateValueFormatter() { }

    public static String format(Object raw, String formatter, String sourceZone, String zoneName) {
        if (raw == null) return null;
        if ("text".equals(formatter)) {
            if (!(raw instanceof String || raw instanceof Number)) throw new IllegalArgumentException("Invalid text value");
            return raw.toString();
        }
        if ("number".equals(formatter)) return new java.math.BigDecimal(raw.toString()).toPlainString();
        if (!"date".equals(formatter) && !"datetime".equals(formatter)) throw new IllegalArgumentException("Unknown formatter");
        if ("date".equals(formatter)) {
            if (raw instanceof LocalDate date) return date.toString();
            if (raw instanceof String text && text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return LocalDate.parse(text).toString();
        }
        ZoneId zone = ZoneId.of(zoneName);
        LocalDateTime time;
        if (raw instanceof Instant instant) time = instant.atZone(zone).toLocalDateTime();
        else if (raw instanceof OffsetDateTime offset) time = offset.atZoneSameInstant(zone).toLocalDateTime();
        else if (raw instanceof String text && (text.endsWith("Z") || text.matches(".*[+-][0-9]{2}:[0-9]{2}$")))
            time = OffsetDateTime.parse(text).atZoneSameInstant(zone).toLocalDateTime();
        else time = localTime(raw).atZone(ZoneId.of(sourceZone)).withZoneSameInstant(zone).toLocalDateTime();
        return "date".equals(formatter) ? time.toLocalDate().toString() : DATE_TIME.format(time);
    }

    private static LocalDateTime localTime(Object raw) {
        if (raw instanceof LocalDateTime time) return time;
        if (raw instanceof String text) return LocalDateTime.parse(text.indexOf('T') >= 0 ? text : text.replace(' ', 'T'));
        if (raw instanceof List<?> parts && parts.size() >= 5 && parts.size() <= 7) {
            int[] values = new int[7];
            for (int i = 0; i < parts.size(); i++) {
                if (!(parts.get(i) instanceof Integer part)) throw new IllegalArgumentException("Invalid date component");
                values[i] = part;
            }
            return LocalDateTime.of(values[0], values[1], values[2], values[3], values[4], values[5], values[6]);
        }
        throw new IllegalArgumentException("Unsupported date representation");
    }
}
