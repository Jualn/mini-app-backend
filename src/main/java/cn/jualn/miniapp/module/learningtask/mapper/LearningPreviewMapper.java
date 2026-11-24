package cn.jualn.miniapp.module.learningtask.mapper;

import cn.jualn.miniapp.module.learningtask.entity.LearningPreview;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LearningPreviewMapper extends BaseMapper<LearningPreview> {
    LearningPreview lock(@Param("id") long id);
    int saveLocked(LearningPreview row);
}

