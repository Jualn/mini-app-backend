package cn.jualn.miniapp.module.media.mapper;

import cn.jualn.miniapp.module.media.bo.MediaAttachmentSimpleBO;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;
import cn.jualn.miniapp.module.media.bo.AttachmentTargetBO;

@Mapper
public interface MediaAttachmentMapper extends BaseMapper<MediaAttachment> {

    @org.apache.ibatis.annotations.Select("SELECT id,target_type,target_id,type,kind,registered,registered_by,object_key,url,original_name,sort_order,content_type,size_bytes,width,height,duration_seconds,verified,retention,expires_at,deleted,created_at FROM media_attachment WHERE id=#{id} FOR UPDATE")
    MediaAttachment lock(@Param("id") long id);

    List<MediaAttachment> selectMine(com.baomidou.mybatisplus.extension.plugins.pagination.Page<MediaAttachment> page,
            @Param("actor") long actor,@Param("legacy") boolean legacy,@Param("types") List<Integer> types,@Param("articleAllowed") boolean articleAllowed,@Param("orgs") List<String> orgs);

    List<Long> readableArticleMedia(@Param("ids") List<Long> ids,@Param("actor") long actor,@Param("articleAllowed") boolean allowed,@Param("orgs") List<String> orgs);
    List<AttachmentAccessRow> selectBatchAccess(@Param("ids") Collection<Long> ids, @Param("actor") long actor);

    int insertBatch(@Param("list") List<MediaAttachment> attachments);

    List<MediaAttachmentSimpleBO> selectSimpleBatchByTargetIds(
            @Param("targetType") Integer targetType,
            @Param("targetIds") Collection<Long> targetIds);

    List<MediaAttachment> selectLinked(@Param("targetType") Integer targetType,
                                       @Param("targetId") Long targetId);

    int deleteLinks(@Param("targetType") Integer targetType, @Param("targetId") Long targetId);

    int insertLink(@Param("targetType") Integer targetType, @Param("targetId") Long targetId,
                   @Param("attachmentId") Long attachmentId, @Param("displayOrder") Integer displayOrder);

    List<AttachmentTargetBO> selectTargets(@Param("attachmentId") Long attachmentId);
}
