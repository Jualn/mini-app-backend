package cn.jualn.miniapp.infrastructure.async.message;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface EventConsumptionMapper {
    int insert(@Param("consumerName") String consumerName, @Param("messageId") String messageId);
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
