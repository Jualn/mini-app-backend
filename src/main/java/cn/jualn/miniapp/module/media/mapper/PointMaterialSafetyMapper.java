package cn.jualn.miniapp.module.media.mapper;

import cn.jualn.miniapp.module.media.entity.PointMaterialRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** The existing file row serializes the single automatic check, including unknown submissions. */
@Mapper
public interface PointMaterialSafetyMapper {
    @Select("SELECT file_id,owner_user_id,kind,state,content_type,size_bytes FROM media_point_file WHERE retained_key=#{retained} FOR UPDATE")
    PointMaterialRow lock(String retained);

    @Select("SELECT safety_audit_log_id FROM media_point_file WHERE file_id=#{file}")
    Long auditId(String file);

    @Update("UPDATE media_point_file SET safety_audit_log_id=#{audit} WHERE file_id=#{file} AND safety_audit_log_id IS NULL AND state='UPLOADING'")
    int bind(@Param("file") String file, @Param("audit") long audit);
}
