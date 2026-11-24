package cn.jualn.miniapp.module.media.mapper;

import cn.jualn.miniapp.module.media.entity.LearningMaterialRow;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.*;

@Mapper
public interface LearningMaterialMapper {
    record Upload(String uploadId, String fingerprint, LocalDateTime expiresAt) {
    }

    record Receipt(String fingerprint, String resultJson) {
    }

    @Select("SELECT fingerprint,result_json FROM media_learning_receipt WHERE owner_user_id=#{user} AND assignment_id=#{assignment} AND request_key=#{key} FOR UPDATE")
    Receipt receipt(@Param("user") long user, @Param("assignment") long assignment, @Param("key") String key);

    @Insert("INSERT INTO media_learning_receipt(owner_user_id,assignment_id,request_key,fingerprint,result_json) VALUES(#{user},#{assignment},#{key},#{hash},#{json})")
    int receiptInsert(@Param("user") long user, @Param("assignment") long assignment, @Param("key") String key, @Param("hash") String hash, @Param("json") String json);

    @Select("SELECT upload_id,fingerprint,expires_at FROM media_learning_upload WHERE owner_user_id=#{user} AND assignment_id=#{assignment} AND request_key=#{key} FOR UPDATE")
    Upload upload(@Param("user") long user, @Param("assignment") long assignment, @Param("key") String key);

    @Insert("INSERT INTO media_learning_upload(upload_id,owner_user_id,assignment_id,request_key,fingerprint,expires_at) VALUES(#{upload},#{user},#{assignment},#{key},#{hash},#{expires})")
    int insertUpload(@Param("upload") String upload, @Param("user") long user, @Param("assignment") long assignment, @Param("key") String key, @Param("hash") String hash, @Param("expires") LocalDateTime expires);

    @Insert("INSERT INTO media_learning_file(file_id,assignment_id,owner_user_id,upload_id,file_index,file_name,kind,content_type,size_bytes,source_key,retained_key,state,created_at,upload_expires_at,cleanup_after) VALUES(#{fileId},#{assignmentId},#{ownerUserId},#{uploadId},#{fileIndex},#{fileName},#{kind},#{contentType},#{sizeBytes},#{sourceKey},#{retainedKey},'UPLOADING',#{createdAt},#{uploadExpiresAt},#{cleanupAfter})")
    int insert(LearningMaterialRow row);

    @Select("SELECT * FROM media_learning_file WHERE file_id=#{id} FOR UPDATE")
    LearningMaterialRow file(long id);

    record Reference(long assignmentId, Long submissionId) {
    }

    @Select("SELECT assignment_id,submission_id FROM media_learning_file WHERE file_id=#{id}")
    Reference reference(long id);

    @Select("SELECT * FROM media_learning_file WHERE retained_key=#{key} FOR UPDATE")
    LearningMaterialRow retained(String key);

    @Select("SELECT * FROM media_learning_file WHERE upload_id=#{upload} ORDER BY file_index FOR UPDATE")
    List<LearningMaterialRow> uploadFiles(String upload);

    @Update("UPDATE media_learning_file SET state='REGISTERED',created_at=#{now},unbound_expires_at=DATE_ADD(#{now},INTERVAL 24 HOUR),cleanup_after=DATE_ADD(#{now},INTERVAL 24 HOUR) WHERE file_id=#{id} AND state='UPLOADING' AND cleanup_after>#{now}")
    int register(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE media_learning_file SET state='BOUND',submission_id=#{submission},unbound_expires_at=NULL WHERE file_id=#{id} AND owner_user_id=#{user} AND assignment_id=#{assignment} AND state='REGISTERED' AND unbound_expires_at>#{now}")
    int bind(@Param("id") long id, @Param("user") long user, @Param("assignment") long assignment, @Param("submission") long submission, @Param("now") LocalDateTime now);

    @Update("UPDATE media_learning_file SET safety_audit_log_id=#{audit} WHERE file_id=#{id} AND safety_audit_log_id IS NULL AND state='UPLOADING'")
    int audit(@Param("id") long id, @Param("audit") long audit);

    @Select("SELECT file_id FROM media_learning_file WHERE state='CLEANING' OR (state IN ('UPLOADING','REGISTERED') AND cleanup_after<=#{now}) ORDER BY file_id LIMIT 100")
    List<Long> expired(LocalDateTime now);

    @Update("UPDATE media_learning_file SET state='CLEANING' WHERE file_id=#{id} AND (state='CLEANING' OR (state IN ('UPLOADING','REGISTERED') AND cleanup_after<=#{now}))")
    int claim(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("UPDATE media_learning_file SET state='DELETED' WHERE file_id=#{id} AND state='CLEANING'")
    int deleted(long id);

    @Update("UPDATE media_learning_file SET state='CLEANING' WHERE file_id=#{id} AND state='DELETED'")
    int retryLateInspectionCleanup(long id);
}
