package cn.jualn.miniapp.module.report.mapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
public interface ReportResolutionMapper {
    record Resolution(Long id,String targetType,Long targetId,String decision,String contentAction,String outcome,Long handlerId,String remark,LocalDateTime handledAt,String reportIds) { }
    @Insert("INSERT INTO report_resolution(target_type,target_id,decision,content_action,outcome,handler_id,remark,handled_at,report_ids) VALUES(#{targetType},#{targetId},#{decision},#{contentAction},#{outcome},#{handlerId},#{remark},#{handledAt},#{reportIds})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    void insert(cn.jualn.miniapp.module.report.entity.ReportResolution resolution);
    @Select("SELECT * FROM report_resolution WHERE target_type=#{type} AND target_id=#{id} ORDER BY id DESC")
    List<Resolution> history(String type,long id);
    @Update("<script>UPDATE report SET outcome=#{outcome} WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    void outcome(java.util.List<Long> ids,String outcome);
}
