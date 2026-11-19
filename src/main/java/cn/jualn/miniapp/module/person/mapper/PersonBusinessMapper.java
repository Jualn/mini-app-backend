package cn.jualn.miniapp.module.person.mapper;

import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.Scope;
@Mapper public interface PersonBusinessMapper {
    cn.jualn.miniapp.module.person.entity.PersonRows.RosterCompleteness completeness(String organization);
    int saveCompleteness(cn.jualn.miniapp.module.person.entity.PersonRows.RosterCompleteness row);
    List<String> rosterDependencies(@Param("organization") String organization,@Param("at") LocalDateTime at);
    List<cn.jualn.miniapp.module.person.entity.PersonRows.Event> rosterHistory(@Param("organization") String organization,@Param("limit") int limit,@Param("offset") long offset);
    long rosterHistoryCount(String organization);
    List<String> activeOrganizations();
    List<String> allOrganizations();
    cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.Roster roster(String organization);
    List<cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes.MemberRef> currentMembers(@Param("organization") String organization,@Param("at") LocalDateTime at);
    Scope scope(String id);
    Scope assignmentScope(String id);
    List<Scope> forUser(long user);
    List<Scope> forAuthority(String authority);
    int insertScope(Scope scope);
    int revokeScope(@Param("id") String id,@Param("actor") long actor,@Param("reason") String reason,@Param("now") LocalDateTime now);
}
