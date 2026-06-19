package cn.jualn.miniapp.module.media.mapper;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

@Mapper
public interface MediaAttachmentMapper extends BaseMapper<MediaAttachment> {

    int insertBatch(@Param("list") List<MediaAttachment> attachments);

    List<MediaAttachmentSimpleBO> selectSimpleBatchByTargetIds(
            @Param("targetType") Integer targetType,
            @Param("targetIds") Collection<Long> targetIds);
}
