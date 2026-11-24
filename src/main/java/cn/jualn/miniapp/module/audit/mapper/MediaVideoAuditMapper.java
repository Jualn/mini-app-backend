package cn.jualn.miniapp.module.audit.mapper;

import cn.jualn.miniapp.module.audit.entity.MediaVideoAudit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

public interface MediaVideoAuditMapper extends BaseMapper<MediaVideoAudit> {
    @Select("SELECT * FROM media_video_audit WHERE id=#{id} FOR UPDATE")
    MediaVideoAudit lock(long id);

    @Select("SELECT * FROM media_video_audit WHERE attachment_id=#{attachmentId} AND policy_key=#{policyKey}")
    MediaVideoAudit find(@Param("attachmentId") long attachmentId,@Param("policyKey") String policyKey);

    // Resource-policy uniqueness arbitrates concurrent registration/publication without rolling back the caller.
    @Insert("""
        INSERT INTO media_video_audit(attachment_id,object_key,size_bytes,policy_key,bucket,region,biz_type,
          snapshot_interval_seconds,operator_id,state,poll_sequence,deadline_at)
        VALUES(#{attachmentId},#{objectKey},#{sizeBytes},#{policyKey},#{bucket},#{region},#{bizType},
          #{snapshotIntervalSeconds},#{operatorId},'PENDING',0,#{deadlineAt})
        ON DUPLICATE KEY UPDATE id=id
        """)
    int reserve(MediaVideoAudit row);
}
