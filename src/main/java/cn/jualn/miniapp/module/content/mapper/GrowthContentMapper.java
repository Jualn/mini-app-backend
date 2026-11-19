package cn.jualn.miniapp.module.content.mapper;

import org.apache.ibatis.annotations.*;

/** Management aggregation read only; effects are applied by Post and Comment owners. */
@Mapper
public interface GrowthContentMapper {
    record Location(String type, long id, String namespace, String organizationId) {}
    java.util.List<Location> locations(@Param("references") java.util.List<cn.jualn.miniapp.module.content.bo.ManagementTarget.Reference> references);

    @Select("SELECT g.post_id FROM post_growth_record g WHERE g.post_id=CASE WHEN #{type}='post' THEN #{id} ELSE (SELECT c.target_id FROM comment c WHERE c.id=#{id} AND c.target_type=1 AND #{type}='comment') END") Long root(String type,long id);
}
