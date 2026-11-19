package cn.jualn.miniapp.module.person.mapper;

import cn.jualn.miniapp.module.person.service.access.DutyCandidateDirectory.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DutyCandidateMapper {
    List<TargetRow> targets(@Param("ceilings") List<Ceiling> ceilings,@Param("types") List<String> types,
        @Param("historical") boolean historical,@Param("partyOnly") boolean partyOnly,
        @Param("platformLabel") String platformLabel,@Param("q") String q,@Param("offset") long offset,@Param("limit") int limit);
    List<Candidate> recipients(@Param("independent") boolean independent,@Param("memberOrganization") String memberOrganization,
        @Param("filter") String filter,@Param("q") String q,@Param("at") LocalDateTime at,@Param("offset") long offset,@Param("limit") int limit);
    List<Organization> organizations(@Param("persons") List<String> persons,@Param("visible") List<String> visible,@Param("at") LocalDateTime at);
    LocalDateTime nextBoundary(LocalDateTime at);
}
