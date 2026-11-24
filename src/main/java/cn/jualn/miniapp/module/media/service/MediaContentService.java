package cn.jualn.miniapp.module.media.service;

import org.springframework.beans.factory.annotation.Autowired;

import cn.jualn.miniapp.module.media.entity.MediaUploadRecord;
import cn.jualn.miniapp.module.media.mapper.MediaUploadRecordMapper;
import cn.jualn.miniapp.module.media.mapper.MediaAttachmentMapper;
import cn.jualn.miniapp.common.enums.TargetType;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.jualn.miniapp.module.media.entity.MediaAttachment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Short local authorization read. No transaction is held while bytes are transmitted. */
@Service @RequiredArgsConstructor
public class MediaContentService {
    private final MediaUploadRecordMapper records;
    private final MediaAttachmentMapper attachments;
    private final List<MediaContentPolicy> policies;
    @Autowired private List<MediaEvidencePolicy> evidencePolicies = List.of();
    @Autowired private GrowthMediaService growth;
    @Autowired private GrowthMediaAuthorization growthAuthorization;

    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public cn.jualn.miniapp.module.media.bo.MediaContentBO require(long id,Long actor,boolean admin) {
        var file=records.selectById(id);
        if(file==null || file.getObjectKey()==null || !MediaObjectKeys.isCommunityRetained(file.getObjectKey()) || !"VERIFIED".equals(file.getDeclaredKind())
                || file.getCleanupStartedAt()!=null || file.getDeclaredSizeBytes()==null || file.getDeclaredSizeBytes()<=0
                || file.getDeclaredSizeBytes()>10485760 || file.getDeclaredContentType()==null
                || !List.of("image/jpeg","image/png","image/webp","image/gif").contains(file.getDeclaredContentType()))throw missing();
        if(file.getStatus()==0 && file.getBoundTargetId()==null && file.getCleanupAfter().isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
                && !admin && actor!=null && actor.equals(file.getUserId())) {
            if(growth.isGrowth(id) && !growth.candidateAllowed(id,actor,growthAuthorization::candidate))throw missing();
            return project(file);
        }
        if(file.getStatus()!=1 || file.getBoundTargetId()==null)throw missing();
        TargetType type=TargetType.fromCode(file.getTargetType());
        if(type==TargetType.POST && attachments.selectCount(new LambdaQueryWrapper<MediaAttachment>()
                .eq(MediaAttachment::getTargetType,type.getCode()).eq(MediaAttachment::getTargetId,file.getBoundTargetId())
                .eq(MediaAttachment::getObjectKey,file.getObjectKey()).eq(MediaAttachment::getDeleted,false))==0 && !(admin && growth.retainedFor(id,file.getBoundTargetId())))throw missing();
        var matching=policies.stream().filter(p->p.targetType()==type).toList();
        if(matching.size()!=1)throw missing();
        boolean allowed=admin?actor!=null && matching.get(0).mayReadAdmin(file.getBoundTargetId(),file.getObjectKey(),actor)
                :matching.get(0).mayRead(file.getBoundTargetId(),file.getObjectKey(),actor);
        if(!allowed && admin && actor!=null) allowed=evidencePolicies.stream().anyMatch(p->p.mayReadEvidence(type,file.getBoundTargetId(),actor));
        if(!allowed)throw missing();return project(file);
    }
    private cn.jualn.miniapp.module.media.bo.MediaContentBO project(MediaUploadRecord file){return new cn.jualn.miniapp.module.media.bo.MediaContentBO(file.getObjectKey(),file.getDeclaredContentType(),file.getDeclaredSizeBytes());}
    public static ContractProblemException missing(){return new ContractProblemException(HttpStatus.NOT_FOUND,"/problems/media-not-found","Media unavailable");}
}
