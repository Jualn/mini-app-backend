package cn.jualn.miniapp.module.interact.mapper;

import cn.jualn.miniapp.module.interact.dto.inner.LikeCountDTO;
import cn.jualn.miniapp.module.interact.entity.LikeRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Set;

public interface LikeRecordMapper extends BaseMapper<LikeRecord> {
    Set<Long> selectLikedTargetIds(
            @Param("userId") Long userId,
            @Param("targetType") Integer targetType,
            @Param("targetIds") List<Long> targetIds
    );

    List<LikeCountDTO> selectLikeCountBatch(
            @Param("targetType") Integer targetType,
            @Param("targetIds")  List<Long> targetIds
    );
}
