package cn.jualn.miniapp.module.interact.mapper;
import java.util.*;
import org.apache.ibatis.annotations.*;
@Mapper
public interface InteractionStatisticsMapper {
 List<InteractionStatisticsRow> counts(@Param("type") int type,@Param("ids") Collection<Long> ids);
}
