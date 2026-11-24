package cn.jualn.miniapp.module.learningtask.mapper;

import cn.jualn.miniapp.module.learningtask.entity.LearningSubmission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LearningSubmissionMapper extends BaseMapper<LearningSubmission> {
    LearningSubmission lock(@Param("id") long id);
    int saveLocked(LearningSubmission row);
}

