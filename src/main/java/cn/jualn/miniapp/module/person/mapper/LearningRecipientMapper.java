package cn.jualn.miniapp.module.person.mapper;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Bounded minimal business projection. Caller must hold Person gate and prove LearningTask audience ceilings. */
@Mapper
public interface LearningRecipientMapper {
    record Candidate(String id,String personId,String name,String disambiguation) {}
    List<Candidate> selectedNames(@Param("account") boolean account, @Param("ids") List<String> ids);
    List<Candidate> organizations(@Param("platform") boolean platform,@Param("ids") List<String> ids,@Param("q") String q,@Param("after") String after,@Param("limit") int limit);
    List<Candidate> candidates(@Param("account") boolean account,@Param("ceilings") List<Target> ceilings,
        @Param("organizations") List<String> organizations,@Param("stages") List<String> stages,
        @Param("q") String q,@Param("after") String after,@Param("limit") int limit,@Param("at") LocalDateTime at);
}
