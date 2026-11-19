package cn.jualn.miniapp.module.person.mapper;

import cn.jualn.miniapp.module.person.entity.QualificationRows.Application;
import cn.jualn.miniapp.module.person.entity.QualificationRows.Entry;
import cn.jualn.miniapp.module.person.entity.QualificationRows.Import;
import cn.jualn.miniapp.module.person.entity.QualificationRows.Mentor;

import cn.jualn.miniapp.module.person.entity.PersonRows.Event;
import cn.jualn.miniapp.module.person.bo.QualificationBO.Answer;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QualificationMapper {
    Import rosterImport(String id);
    int insertImport(Import row);
    int saveImport(Import row);
    int savePlan(Import row);
    int purgeFiles();
    Entry entry(String id);
    List<Entry> entriesAfter(@Param("after") String after,@Param("limit") int limit);
    List<Entry> byStudent(String digest);
    long linkedUser(long user);
    int insertEntry(Entry row);
    int saveEntry(Entry row);
    Application application(long user);
    Application applicationForBinding(String binding);
    long applicationEventVersion(String application);
    List<Application> applications(@Param("user") long user,@Param("limit") int limit,@Param("offset") long offset);
    long applicationCount(long user);
    int insertApplication(Application row);
    Mentor mentor(String person);
    List<Mentor> mentors();
    List<Mentor> mentorPage(@Param("q") String q,@Param("status") String status,@Param("bindingState") String bindingState,@Param("at") java.time.LocalDateTime at,@Param("limit") int limit,@Param("offset") long offset);
    long mentorCount(@Param("q") String q,@Param("status") String status,@Param("bindingState") String bindingState,@Param("at") java.time.LocalDateTime at);
    long mentorListCorruption(@Param("q") String q,@Param("status") String status,@Param("bindingState") String bindingState,@Param("at") java.time.LocalDateTime at);
    int insertMentor(Mentor row);
    int saveMentor(Mentor row);
    List<Event> history(@Param("type") String type,@Param("id") String id,@Param("limit") int limit,@Param("offset") long offset);
    long historyCount(@Param("type") String type,@Param("id") String id);
    List<Answer> answers(@Param("person") String person,@Param("author") Long author,@Param("limit") int limit,@Param("offset") long offset);
    long answerCount(@Param("person") String person,@Param("author") Long author);
    default long answerCount(String person) { return answerCount(person,null); }
    long answerCorruption(@Param("person") String person,@Param("author") Long author);
    long identityHistory(String person);
    long mentorCorruption(String person);
    long entryCorruption(String id);
    long importCorruption(String id);
    List<Import> imports(long actor);
    int completeUploadReceipt(cn.jualn.miniapp.module.person.entity.PersonRows.Receipt receipt);
    int abandonUpload(@Param("actor") long actor,@Param("organization") String organization,@Param("key") String key,@Param("fingerprint") String fingerprint);
    String importResult(String id);
    long committedImportCount(String organization);
    record CommittedImport(String importId, String sourceReference, java.time.LocalDateTime committedAt, int importedCount) {}
    List<CommittedImport> committedImports(
            @Param("organization") String organization, @Param("limit") int limit, @Param("offset") long offset);
    int restartUpload(@Param("id") String id,@Param("at") java.time.LocalDateTime at);
}
