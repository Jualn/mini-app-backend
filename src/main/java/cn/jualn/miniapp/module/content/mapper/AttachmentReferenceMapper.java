package cn.jualn.miniapp.module.content.mapper;
/** Bounded read-only cross-owner projection, consistent with the content aggregation boundary. */
@org.apache.ibatis.annotations.Mapper
public interface AttachmentReferenceMapper {
 @org.apache.ibatis.annotations.Select("SELECT (SELECT COUNT(*) FROM event_attachment_link WHERE attachment_id=#{id}) + (SELECT COUNT(*) FROM activity WHERE cover_attachment_id=#{id}) + (SELECT COUNT(*) FROM public_event WHERE cover_attachment_id=#{id}) + (SELECT COUNT(*) FROM article WHERE cover_attachment_id=#{id}) + (SELECT COUNT(*) FROM official_content WHERE video_attachment_id=#{id}) + (SELECT COUNT(*) FROM event_action WHERE attachment_id=#{id}) + (SELECT COUNT(*) FROM home_banner WHERE cover_attachment_id=#{id})")
 long count(@org.apache.ibatis.annotations.Param("id") long id);
}
