package cn.jualn.miniapp.module.interact.service;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.module.interact.bo.InteractionStatisticsBO;
import java.util.*;
/** Fact projections only; owning subjects decide visibility. No dependency back on a subject service. */
public interface InteractionStatisticsService {
 Map<Long,InteractionStatisticsBO> counts(TargetType type,Collection<Long> targetIds);
}
