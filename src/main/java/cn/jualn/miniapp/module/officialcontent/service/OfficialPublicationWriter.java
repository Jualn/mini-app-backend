package cn.jualn.miniapp.module.officialcontent.service;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.admin.operation.service.AdminOperationLogService;
import cn.jualn.miniapp.module.officialcontent.entity.OfficialContent;
import cn.jualn.miniapp.module.officialcontent.mapper.OfficialContentMapper;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.web.StrongEtag;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Only state changes: no attachment rebinding, content edit, notification or artificial approval. */
@Service @RequiredArgsConstructor
public class OfficialPublicationWriter {
    private final OfficialContentMapper mapper;
    private final CurrentAuthorization authorization;
    private final AdminOperationLogService audit;
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public OfficialContent commit(long actor,long id,boolean publish,String ifMatch,long checkedRevision) {
        authorization.requireWork(actor,"OFFICIAL_CONTENT_PUBLISH","OFFICIAL_CONTENT",publish?new String[]{"official-content:read","media:read","official-content:publish"}:new String[]{"official-content:unpublish"});
        var value=mapper.lock(id);
        if(value==null) throw new ContractProblemException(org.springframework.http.HttpStatus.NOT_FOUND,"/problems/not-found","Content is unavailable");
        StrongEtag.require(ifMatch,"official-content",id,value.getRevision());
        if(value.getRevision()!=checkedRevision) throw new ContractProblemException(org.springframework.http.HttpStatus.PRECONDITION_FAILED,"/problems/precondition-failed","Content changed during safety checks");
        String desired=publish?"PUBLISHED":"UNPUBLISHED",before=value.getPublishStatus();
        if(!java.util.Set.of("DRAFT","PUBLISHED","UNPUBLISHED").contains(before) || !publish && "DRAFT".equals(before))
            throw ContractProblemException.conflict("official-content-state-conflict","Cannot unpublish a draft");
        if(desired.equals(before)) return value;
        var now=java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).withNano(0);
        value.setPublishStatus(desired);value.setRevision(Math.addExact(value.getRevision(),1L));
        value.setUpdatedBy(actor);value.setUpdatedAt(now);
        if(publish && value.getPublishedAt()==null) value.setPublishedAt(now);
        if(mapper.updatePublication(value)!=1) throw new IllegalStateException("Official publication update failed");
        audit.recordOfficialContentStatus(actor,id,before,desired);
        return value;
    }
}
