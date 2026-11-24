package cn.jualn.miniapp.module.media.mapper;

import cn.jualn.miniapp.module.media.entity.PointMaterialRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PointMaterialMapper {
    record Upload(String uploadId,String fingerprint,LocalDateTime expiresAt) {}
    record Receipt(String fingerprint,String resultJson) {}
    @Select("SELECT upload_id,fingerprint,expires_at FROM media_point_upload WHERE owner_user_id=#{user} AND request_key=#{key} FOR UPDATE")
    Upload upload(@Param("user")long user,@Param("key")String key);
    @Insert("INSERT INTO media_point_upload(upload_id,owner_user_id,request_key,fingerprint,expires_at) VALUES(#{id},#{user},#{key},#{hash},#{expires})")
    void insertUpload(@Param("id")String id,@Param("user")long user,@Param("key")String key,@Param("hash")String hash,@Param("expires")LocalDateTime expires);
    @Update("UPDATE media_point_upload SET expires_at=#{expires} WHERE upload_id=#{id}")
    void uploadExpiry(@Param("id")String id,@Param("expires")LocalDateTime expires);
    void fileExpiry(@Param("id")String id,@Param("expires")LocalDateTime expires);
    void insertFile(PointMaterialRow file);
    PointMaterialRow file(@Param("id")String id);
    PointMaterialRow index(@Param("upload")String upload,@Param("index")int index);
    int registered(@Param("id")String id,@Param("now")LocalDateTime now);
    int bind(@Param("id")String id,@Param("user")long user,@Param("application")long application,@Param("now")LocalDateTime now);
    @Select("SELECT fingerprint,result_json FROM media_point_receipt WHERE owner_user_id=#{user} AND request_key=#{key} FOR UPDATE")
    Receipt receipt(@Param("user")long user,@Param("key")String key);
    @Insert("INSERT INTO media_point_receipt(owner_user_id,request_key,fingerprint,file_id,result_json) VALUES(#{user},#{key},#{hash},#{file},#{json})")
    void insertReceipt(@Param("user")long user,@Param("key")String key,@Param("hash")String hash,@Param("file")String file,@Param("json")String json);
    @Insert("INSERT INTO media_point_access_audit(actor_user_id,file_id,application_id,accessed_at) VALUES(#{actor},#{file},#{application},#{now})")
    void audit(@Param("actor")long actor,@Param("file")String file,@Param("application")long application,@Param("now")LocalDateTime now);
    List<PointMaterialRow> expired(@Param("now")LocalDateTime now);
    List<PointMaterialRow> expiredBoundSources(@Param("now")LocalDateTime now);
    @Update("UPDATE media_point_file SET source_deleted=TRUE WHERE file_id=#{id} AND state='BOUND'")
    int sourceDeleted(String id);
    int claim(@Param("id")String id,@Param("now")LocalDateTime now);
    @Update("UPDATE media_point_file SET state='DELETED' WHERE file_id=#{id} AND state='CLEANING'")
    int deleted(String id);
}
