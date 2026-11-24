package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.module.points.bo.PointsBO.Summary;
import cn.jualn.miniapp.module.points.bo.PointsBO.Totals;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import static cn.jualn.miniapp.module.points.PointsProblems.conflict;

public final class PointsAmounts {
    public static final long MAX=9_007_199_254_740_991L;
    private PointsAmounts() {}
    public static long checked(BigDecimal value) {
        if(value==null || value.signum()<0 || value.compareTo(BigDecimal.valueOf(MAX))>0) throw conflict("overflow");
        try { return value.longValueExact(); }
        catch(ArithmeticException invalid) { throw conflict("overflow"); }
    }
    public static Summary summary(String personId,Totals totals,LocalDateTime now) {
        long granted=checked(totals.granted()),reversed=checked(totals.reversed());
        if(reversed>granted) throw conflict("negative-balance");
        return new Summary(personId,granted-reversed,granted,reversed,now);
    }
    public static void adding(String personId,Totals totals,int delta,LocalDateTime now) {
        var granted=totals.granted();var reversed=totals.reversed();
        if(delta>0) granted=granted.add(BigDecimal.valueOf(delta));
        else reversed=reversed.subtract(BigDecimal.valueOf(delta));
        summary(personId,new Totals(granted,reversed),now);
    }
}
