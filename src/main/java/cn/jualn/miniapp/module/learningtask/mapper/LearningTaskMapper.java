package cn.jualn.miniapp.module.learningtask.mapper;

import cn.jualn.miniapp.module.learningtask.entity.LearningTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.AdminQuery;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReadScope;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LearningTaskMapper extends BaseMapper<LearningTask> {
    @Select("SELECT id FROM learning_task WHERE article_id=#{article} LIMIT 1 FOR SHARE")
    List<Long> retainedArticleReferences(long article);

    @Select("SELECT id FROM learning_task WHERE article_id=#{article} AND status='PUBLISHED' LIMIT 1 FOR SHARE")
    List<Long> activeArticleReferences(long article);

    LearningTask lock(@Param("id") long id);

    int saveLocked(LearningTask row);

    List<LearningTask> adminPage(@Param("query") AdminQuery query, @Param("scope") ReadScope scope,
                                 @Param("offset") long offset);

    long adminCount(@Param("query") AdminQuery query, @Param("scope") ReadScope scope);
}

