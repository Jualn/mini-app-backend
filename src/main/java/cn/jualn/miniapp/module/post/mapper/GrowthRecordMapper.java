package cn.jualn.miniapp.module.post.mapper;

import cn.jualn.miniapp.module.post.bo.GrowthRecordBO.Link;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface GrowthRecordMapper {
    @Select("SELECT post_id,person_id,organization_id,record_type FROM post_growth_record WHERE post_id=#{id}") Link find(long id);
    @Insert("INSERT INTO post_growth_record(post_id,person_id,organization_id,record_type) VALUES(#{postId},#{personId},#{organizationId},#{recordType})") int insert(Link row);
    record Query(String person,String organization,String type,String viewer,List<String> memberOrganizations,java.time.LocalDateTime time,Long id,int limit) {}
    @Select("<script>SELECT p.id FROM post p JOIN post_growth_record g ON g.post_id=p.id WHERE p.deleted_at IS NULL <if test='person!=null'>AND g.person_id=#{person}</if><if test='organization!=null'>AND g.organization_id=#{organization}</if><if test='type!=null'>AND g.record_type=#{type}</if> AND (g.person_id=#{viewer} OR (g.organization_id IN <foreach collection='memberOrganizations' item='org' open='(' separator=',' close=')'>#{org}</foreach> AND p.admin_hidden=FALSE AND p.status=2 AND (p.audit_status=1 OR (p.audit_status=0 AND p.community_version=TRUE AND p.temporary_visible_until&gt;NOW(6))))) <if test='time!=null'>AND (p.published_at&lt;#{time} OR (p.published_at=#{time} AND p.id&lt;#{id}))</if> ORDER BY p.published_at DESC,p.id DESC LIMIT #{limit}</script>") List<Long> page(Query query);
    @Select("SELECT DISTINCT organization_id FROM post_growth_record WHERE (#{person} IS NULL OR person_id=#{person})") List<String> organizations(String person);
}
