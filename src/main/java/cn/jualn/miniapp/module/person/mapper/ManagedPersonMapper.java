package cn.jualn.miniapp.module.person.mapper;

import cn.jualn.miniapp.module.person.entity.ManagedPersonRows.Binding;
import cn.jualn.miniapp.module.person.entity.ManagedPersonRows.Identifier;
import cn.jualn.miniapp.module.person.entity.ManagedPersonRows.Profile;
import cn.jualn.miniapp.module.person.entity.ManagedPersonRows.ProtectedValue;
import cn.jualn.miniapp.module.person.entity.ManagedPersonRows.Registration;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Audit;
import cn.jualn.miniapp.module.person.bo.ManagedPersonBO.Namespace;
import cn.jualn.miniapp.module.person.entity.PersonRows.Event;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ManagedPersonMapper {
    Profile profile(String id);

    List<String> people();

    int insertProfile(Profile row);

    int updateProfile(Profile row);

    Registration registration(String person);

    int saveRegistration(Registration row);

    List<Identifier> identifiers(String person);

    Identifier identifier(@Param("person") String person, @Param("id") String id);

    int insertIdentifier(Identifier row);

    int updateIdentifier(Identifier row);

    String occupation(@Param("type") String type, @Param("namespace") String namespace, @Param("digest") String digest);

    int occupy(@Param("type") String type, @Param("namespace") String namespace, @Param("digest") String digest, @Param("person") String person);

    int saveIdentifierVersion(Identifier row);

    ProtectedValue identifierValue(@Param("id") String id, @Param("revision") long revision);

    Binding binding(String id);

    long confirmedAdminBinding(String id);

    Binding personBinding(String person);

    Binding accountBinding(long user);

    List<Binding> bindingHistory(@Param("person") String person, @Param("limit") int limit, @Param("offset") long offset);

    long bindingHistoryCount(String person);

    long accountSlot(long user);

    int advanceAccountSlot(long user);

    int advancePersonSlot(String person);

    int insertBinding(Binding row);

    int endBinding(Binding row);

    boolean everBound(@Param("person") String person, @Param("user") long user);

    int insertEvent(@Param("event") Event event, @Param("person") String person,
                    @Param("before") String before, @Param("after") String after);

    int completeEvent(@Param("id") String id, @Param("receipt") String receipt, @Param("result") String result);

    List<Audit> history(@Param("person") String person, @Param("limit") int limit, @Param("offset") long offset);

    long historyCount(String person);
}
