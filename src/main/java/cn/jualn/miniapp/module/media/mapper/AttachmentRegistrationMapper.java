package cn.jualn.miniapp.module.media.mapper;
import org.apache.ibatis.annotations.*;
@Mapper
public interface AttachmentRegistrationMapper {
 @Insert("INSERT INTO media_attachment_registration(operator_id,request_key,fingerprint) VALUES(#{actor},#{key},#{fingerprint}) ON DUPLICATE KEY UPDATE operator_id=operator_id")
 int reserve(@Param("actor") long actor,@Param("key") String key,@Param("fingerprint") String fingerprint);
 @Select("SELECT fingerprint,attachment_id FROM media_attachment_registration WHERE operator_id=#{actor} AND request_key=#{key} FOR UPDATE")
 Registration lock(@Param("actor") long actor,@Param("key") String key);
 @Update("UPDATE media_attachment_registration SET attachment_id=#{id} WHERE operator_id=#{actor} AND request_key=#{key} AND attachment_id IS NULL")
 int complete(@Param("actor") long actor,@Param("key") String key,@Param("id") long id);
 record Registration(String fingerprint,Long attachmentId) {}
}
