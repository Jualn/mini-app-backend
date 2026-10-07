package cn.jualn.miniapp.infrastructure.async.message;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface DeadMessageMapper {
    int upsert(DeadMessageRecord record);

    List<DeadMessageRecord> selectRecent(@Param("limit") int limit);

    int deleteBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
