package cn.jualn.miniapp.module.admin.operation.mapper;
import cn.jualn.miniapp.module.admin.operation.bo.AdminAuditBO.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface AdminAuditMapper {
    @Select("SELECT id FROM admin_audit_guard WHERE id=1 FOR UPDATE") Integer lock();
    @Insert("INSERT INTO admin_audit_record(id,kind,source,operation,actor_user,target_user,target_person,target_type,target_id,result,recorded_at,payload) VALUES(#{id},#{kind},#{source},#{operation},#{actorUser},#{targetUser},#{targetPerson},#{targetType},#{targetId},#{result},#{recordedAt},#{payload})") int insert(Row row);
    @Select("SELECT COALESCE(MAX(sequence),0) FROM admin_audit_record") long highWater();
    @Select("SELECT r.* FROM admin_audit_record r WHERE r.kind='DUTY' AND JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.after.status'))='ACTIVE' AND JSON_TYPE(JSON_EXTRACT(r.payload,'$.after.expiresAt'))='STRING' AND CAST(REPLACE(REPLACE(JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.after.expiresAt')),'T',' '),'Z','') AS DATETIME(6)) <= #{now} AND NOT EXISTS(SELECT 1 FROM admin_audit_record done WHERE done.id=CONCAT('D:expiry:',r.id) OR done.id=CONCAT('D:source:',r.id)) AND NOT EXISTS(SELECT 1 FROM admin_audit_record closed WHERE closed.kind='DUTY' AND closed.source=r.source AND closed.target_id=r.target_id AND closed.sequence>r.sequence AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(closed.payload,'$.after.status')),'')!='ACTIVE') ORDER BY r.sequence LIMIT 1000")
    List<Row> expiryCandidates(java.time.LocalDateTime now);
    @Select("SELECT COUNT(*) FROM admin_audit_record WHERE id=#{id}") int exists(String id);
    @Select("SELECT r.* FROM admin_audit_record r WHERE r.kind='DUTY' AND JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.after.status'))='ACTIVE' AND JSON_CONTAINS(JSON_EXTRACT(r.payload,'$.relatedRecords'),JSON_OBJECT('kind',#{kind},'id',#{id})) AND NOT(JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.relatedRecords[0].kind'))=#{kind} AND JSON_UNQUOTE(JSON_EXTRACT(r.payload,'$.relatedRecords[0].id'))=#{id}) AND NOT EXISTS(SELECT 1 FROM admin_audit_record closed WHERE closed.kind='DUTY' AND closed.source=r.source AND closed.target_id=r.target_id AND closed.sequence>r.sequence AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(closed.payload,'$.after.status')),'')!='ACTIVE') ORDER BY r.sequence")
    List<Row> dependent(@Param("kind") String kind,@Param("id") String id);
    List<Row> page(@Param("q") Query query,@Param("high") long high,@Param("time") java.time.LocalDateTime time,@Param("last") String last);
    @Select("SELECT * FROM admin_audit_record WHERE kind='DUTY' AND source=#{source} AND target_id=#{target} ORDER BY sequence DESC LIMIT 1")
    Row latestDuty(@Param("source") String source,@Param("target") String target);
    record SourceStart(String source,java.time.LocalDateTime availableSince) {}
    @Select("SELECT source,MIN(recorded_at) AS available_since FROM admin_audit_record WHERE kind=#{kind} GROUP BY source") List<SourceStart> coverage(String kind);
}
