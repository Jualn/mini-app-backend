package cn.jualn.miniapp.module.learningtask.mapper;

import cn.jualn.miniapp.module.learningtask.entity.LearningAssignment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LearningAssignmentMapper extends BaseMapper<LearningAssignment> {
    LearningAssignment lock(@Param("id") long id);
    int saveLocked(LearningAssignment row);
}

