package cn.jualn.miniapp.module.media.mapper;

import org.apache.ibatis.annotations.*;
import cn.jualn.miniapp.module.media.entity.GrowthMediaRow;
import java.util.List;

@Mapper
public interface GrowthMediaMapper {
    @Insert("INSERT INTO growth_media_context(upload_id,file_index,owner_user_id,person_id,organization_id,record_type,editing_post_id,source_key,original_name,content_type,size_bytes,created_at,expires_at) VALUES(#{uploadId},#{fileIndex},#{ownerUserId},#{personId},#{organizationId},#{recordType},#{editingPostId},#{sourceKey},#{originalName},#{contentType},#{sizeBytes},#{createdAt},#{expiresAt})") int insert(GrowthMediaRow row);
    @Select("SELECT * FROM growth_media_context WHERE upload_id=#{id} ORDER BY file_index") List<GrowthMediaRow> upload(String id);
    @Select("SELECT * FROM growth_media_context WHERE media_id=#{id}") GrowthMediaRow file(long id);
    @Select("SELECT * FROM growth_media_context WHERE upload_id=#{upload} AND file_index=#{index} FOR UPDATE") GrowthMediaRow lock(String upload,int index);
    @Update("UPDATE growth_media_context SET media_id=#{id} WHERE upload_id=#{upload} AND file_index=#{index} AND media_id IS NULL") int register(String upload,int index,long id);
    @Update("UPDATE growth_media_context SET registered_at=#{at},unbound_expires_at=DATE_ADD(#{at},INTERVAL 24 HOUR) WHERE upload_id=#{upload} AND file_index=#{index} AND registered_at IS NULL") int completeRegistration(String upload,int index,java.time.LocalDateTime at);
    @Update("UPDATE growth_media_context SET bound_post_id=#{post},unbound_expires_at=NULL WHERE media_id=#{id} AND registered_at IS NOT NULL AND (bound_post_id IS NULL OR bound_post_id=#{post})") int bind(long id,long post);
    @Select("SELECT EXISTS(SELECT 1 FROM growth_media_context g WHERE g.source_key=#{key} AND BINARY g.source_key=BINARY #{key}) OR EXISTS(SELECT 1 FROM media_upload_record u JOIN growth_media_context g ON g.media_id=u.id WHERE u.object_key=#{key} AND BINARY u.object_key=BINARY #{key})") long reserved(String key);
}
