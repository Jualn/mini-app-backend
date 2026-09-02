package cn.jualn.miniapp.module.media.mapper;

import cn.jualn.miniapp.module.media.entity.MediaUploadRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Mapper
public interface MediaUploadRecordMapper extends BaseMapper<MediaUploadRecord> {

    int insertBatch(@Param("list") List<MediaUploadRecord> records);

    int bindPending(
            @Param("objectKeys") Collection<String> objectKeys,
            @Param("userId") Long userId,
            @Param("targetType") Integer targetType,
            @Param("targetId") Long targetId,
            @Param("now") LocalDateTime now);
}
