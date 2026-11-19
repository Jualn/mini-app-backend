package cn.jualn.miniapp.module.audit.mapper;
@org.apache.ibatis.annotations.Mapper
public interface AttachmentHistoryMapper {
 @org.apache.ibatis.annotations.Select("SELECT EXISTS(SELECT 1 FROM media_video_audit WHERE attachment_id=#{id}) OR EXISTS(SELECT 1 FROM content_safety_review WHERE JSON_SEARCH(snapshot_attachments,'one',#{url}) IS NOT NULL)")
 boolean retained(@org.apache.ibatis.annotations.Param("id") long id,@org.apache.ibatis.annotations.Param("url") String url);
}
