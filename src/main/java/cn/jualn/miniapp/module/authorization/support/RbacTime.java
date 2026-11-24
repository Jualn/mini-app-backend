package cn.jualn.miniapp.module.authorization.support;

import java.time.*;
import java.util.regex.*;

/** UTC wire times; query endpoints translate boundaries exactly onto the database microsecond grid. */
public final class RbacTime {
    private static final Pattern OFFSET=Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})[Tt](\\d{2}:\\d{2}:\\d{2})(?:[.](\\d+))?([Zz]|[+-]\\d{2}:\\d{2})$");
    private RbacTime() {}
    /** Validate the raw public expiry before parsing; never round or truncate a write. */
    public static LocalDateTime expiry(String value) {
        if(value==null) return null;
        try {
            Matcher m=OFFSET.matcher(value);
            if(!m.matches() || m.group(3)!=null && m.group(3).length()>6) throw new IllegalArgumentException();
            LocalDateTime local=LocalDateTime.parse(m.group(1)+"T"+m.group(2)+(m.group(3)==null?"":"."+m.group(3)));
            String offset=m.group(4);
            int seconds=0;
            if(!offset.equalsIgnoreCase("Z")) {
                int hours=Integer.parseInt(offset.substring(1,3)),minutes=Integer.parseInt(offset.substring(4,6));
                if(hours>23 || minutes>59) throw new IllegalArgumentException();
                seconds=(hours*3600+minutes*60)*(offset.charAt(0)=='-'?-1:1);
            }
            LocalDateTime utc=local.minusSeconds(seconds);
            // DATETIME(6) stores UTC civil years 1000 through 9999, including after offset conversion.
            if(utc.getYear()<1000 || utc.getYear()>9999) throw new IllegalArgumentException();
            return utc;
        } catch(DateTimeException | IllegalArgumentException failure) { throw RbacProblems.invalid("expiresAt"); }
    }
    public static String wire(LocalDateTime utc) { return utc==null?null:utc.toInstant(ZoneOffset.UTC).toString(); }
    public static void range(String after,String before) {
        if(after!=null && before!=null && exact(after).compareTo(exact(before))>=0) throw RbacProblems.invalid("date-time");
    }
    private static java.math.BigDecimal exact(String value) {
        try {
            Matcher m=OFFSET.matcher(value);if(!m.matches()) throw new IllegalArgumentException();
            long seconds=OffsetDateTime.parse(m.group(1)+"T"+m.group(2)+m.group(4).toUpperCase(java.util.Locale.ROOT)).toEpochSecond();
            return java.math.BigDecimal.valueOf(seconds).add(m.group(3)==null?java.math.BigDecimal.ZERO:new java.math.BigDecimal("0."+m.group(3)));
        } catch(DateTimeException | IllegalArgumentException failure) { throw RbacProblems.invalid("date-time"); }
    }
    public static LocalDateTime queryBoundary(String value) {
        if(value==null) return null;
        try {
            Matcher m=OFFSET.matcher(value);if(!m.matches()) throw new IllegalArgumentException();
            String fraction=m.group(3)==null?"":m.group(3);
            String micro=(fraction+"000000").substring(0,6);
            String zone=m.group(4).toUpperCase(java.util.Locale.ROOT);
            var instant=OffsetDateTime.parse(m.group(1)+"T"+m.group(2)+"."+micro+zone).toInstant();
            // For stored microsecond values, >= x and < x use the same ceil-to-micro boundary.
            if(fraction.length()>6 && fraction.substring(6).chars().anyMatch(c->c!='0')) instant=instant.plusNanos(1000);
            return LocalDateTime.ofInstant(instant,ZoneOffset.UTC);
        } catch(DateTimeException | IllegalArgumentException failure) { throw RbacProblems.invalid("date-time"); }
    }
}
