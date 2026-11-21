package cn.jualn.miniapp.module.person.mapper;

import cn.jualn.miniapp.module.person.bo.MentorWorkflowBO.AccountOption;
import cn.jualn.miniapp.module.person.bo.MentorWorkflowBO.Candidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface MentorWorkflowMapper {
    List<Candidate> candidates(@Param("q") String q, @Param("offset") long offset, @Param("limit") int limit);
    long candidateCount(String q);
    List<AccountOption> accounts(@Param("q") String q, @Param("person") String person, @Param("actor") long actor,
                                 @Param("offset") long offset, @Param("limit") int limit);
    long accountCount(@Param("q") String q, @Param("person") String person, @Param("actor") long actor);
    String nickname(long user);
}
