package cn.jualn.miniapp.module.home.mapper;

import cn.jualn.miniapp.module.home.model.HomeContent.Type;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface HomeBannerMapper {
    record Row(Type targetType, long targetId, boolean enabled, String titleOverride, Long coverAttachmentId) {}

    @Select("SELECT revision FROM home_banner_configuration WHERE id = 1")
    Long revision();

    @Select("SELECT revision FROM home_banner_configuration WHERE id = 1 FOR UPDATE")
    Long lockRevision();

    @Select("SELECT target_type, target_id, enabled, title_override, cover_attachment_id FROM home_banner ORDER BY position")
    List<Row> items();

    @Delete("DELETE FROM home_banner")
    void clear();

    @Insert("""
            INSERT INTO home_banner (position, target_type, target_id, enabled, title_override, cover_attachment_id)
            VALUES (#{position}, #{row.targetType}, #{row.targetId}, #{row.enabled}, #{row.titleOverride}, #{row.coverAttachmentId})
            """)
    void insert(@Param("position") int position, @Param("row") Row row);

    @Update("UPDATE home_banner_configuration SET revision = revision + 1 WHERE id = 1")
    int advanceRevision();
}
