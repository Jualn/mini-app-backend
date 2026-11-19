package cn.jualn.miniapp.module.officialcontent.mapper;

import cn.jualn.miniapp.module.officialcontent.entity.OfficialContent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface OfficialContentMapper extends BaseMapper<OfficialContent> {
    @Select("SELECT id,title,summary,body,publisher,category,publish_status,video_attachment_id,created_by,updated_by,revision,published_at,created_at,updated_at FROM official_content WHERE id=#{id} FOR UPDATE")
    OfficialContent lock(@Param("id") long id);
    @Update("UPDATE official_content SET title=#{title},summary=#{summary},body=#{body},publisher=#{publisher},category=#{category},publish_status=#{publishStatus},video_attachment_id=#{videoAttachmentId},updated_by=#{updatedBy},revision=#{revision},published_at=#{publishedAt},updated_at=#{updatedAt} WHERE id=#{id}")
    int replace(OfficialContent entity);
    @Update("UPDATE official_content SET publish_status=#{publishStatus},revision=#{revision},published_at=#{publishedAt},updated_by=#{updatedBy},updated_at=#{updatedAt} WHERE id=#{id}")
    int updatePublication(OfficialContent entity);
    List<OfficialContent> list(@Param("category") String category, @Param("status") String status,
            @Param("lastId") Long lastId, @Param("limit") int limit);
}
