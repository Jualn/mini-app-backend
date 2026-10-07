package cn.jualn.miniapp.module.eventcontent.service;

import cn.jualn.miniapp.common.exception.BusinessException;
import cn.jualn.miniapp.common.result.ResultCode;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Shared semantics for event times; no persistence or presentation formatting. */
public final class EventTimePolicy {
    private EventTimePolicy() {}

    public static int precision(LocalDateTime value, Integer precision) {
        int result = precision == null ? (value == null ? 0 : 2) : precision;
        if (result < 0 || result > 2 || (value == null && result != 0))
            throw invalid("时间与精度不匹配");
        if (value != null && result == 1 && !LocalTime.MIDNIGHT.equals(value.toLocalTime()))
            throw invalid("日期精度请提交当天零点");
        return result;
    }

    public static void range(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && end.isBefore(start)) throw invalid("结束时间不能早于开始时间");
    }

    public static void range(LocalDateTime start, LocalDateTime end, Integer endPrecision) {
        if (start == null || end == null) return;
        if (Integer.valueOf(1).equals(endPrecision)) {
            if (!start.isBefore(end.toLocalDate().plusDays(1).atStartOfDay()))
                throw invalid("结束日期不能早于开始时间");
        } else range(start, end);
    }

    public static LocalDateTime exclusiveEnd(LocalDateTime end, Integer precision) {
        if (end == null || precision == null || precision == 0) return null;
        return precision == 1 ? end.toLocalDate().plusDays(1).atStartOfDay() : end;
    }

    public static String registration(int publication, Integer mode, LocalDateTime start, Integer startPrecision,
                                      LocalDateTime end, Integer endPrecision, LocalDateTime now) {
        if (publication != 1) return "UNAVAILABLE";
        if (mode == null || mode == 0) return "UNKNOWN";
        if (mode == 1) return "NOT_REQUIRED";
        if (mode == 2 || mode == 4) return "UNAVAILABLE"; // Forms enabled in a separate phase.
        LocalDateTime boundary = exclusiveEnd(end, endPrecision);
        if (boundary != null && !now.isBefore(boundary)) return "CLOSED";
        if (start != null && startPrecision != null && startPrecision > 0 && now.isBefore(start)) return "UPCOMING";
        if (start == null || startPrecision == null || startPrecision == 0 || boundary == null) return "UNKNOWN";
        return "OPEN";
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(ResultCode.INVALID_OPERATION, message);
    }
}
