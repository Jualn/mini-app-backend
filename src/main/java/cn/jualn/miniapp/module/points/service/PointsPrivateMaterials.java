package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.module.media.bo.PointMaterialBO;
import cn.jualn.miniapp.module.media.service.PointMaterialService;
import cn.jualn.miniapp.module.points.bo.PointsBO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Points purpose boundary over the media owner's private lifecycle. */
@Service @RequiredArgsConstructor
public class PointsPrivateMaterials {
    private final PointMaterialService media;
    private final cn.jualn.miniapp.module.points.mapper.PointsMapper applications;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    public PointMaterialBO.Credentials upload(long user,List<PointMaterialBO.Declaration> files,String key) { return media.upload(user,files,key); }
    public PointMaterialBO.File register(long user,String upload,int index,String key) { return media.register(user,upload,index,key); }
    public PointMaterialBO.File read(long user,String file) { var result=media.read(user,file);association(result);return result; }
    public PointMaterialBO.File bound(String file) { var result=media.bound(file);association(result);return result; }
    public PointMaterialBO.File candidate(String file) { var result=media.candidate(file);association(result);return result; }
    public PointMaterialBO.Access draftAccess(long user,String file) {return media.draftAccess(user,file);}
    public PointMaterialBO.Access access(long user,String file) { read(user,file);return media.access(user,file); }
    public PointMaterialBO.Access reviewAccess(long actor,String file,long application,long owner) { association(media.bound(file));return media.reviewAccess(actor,file,application,owner); }
    public void bind(long user,long application,List<String> files) {
        for(String file:files) association(file,user,application);
        media.bind(user,application,files);
    }
    public void require(PointsBO.Application application) {
        for(String file:application.fileIds()) association(file,application.applicantUserId(),application.applicationId());
        media.require(application.applicantUserId(),application.applicationId(),application.fileIds());
    }
    private void association(PointMaterialBO.File file) {
        if(file.applicationId()!=null) association(file.fileId(),file.ownerUserId(),file.applicationId());
    }
    private void association(String file,long user,long application) {
        var row=applications.application(application);
        try {
            if(row==null || row.getApplicantUserId()!=user) throw closed();
            var references=json.readTree(row.getFileIds());
            if(!references.isArray()) throw closed();
            for(var reference:references) if(reference.isTextual() && file.equals(reference.textValue())) return;
            throw closed();
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw closed(); }
    }
    private cn.jualn.miniapp.common.exception.ContractProblemException closed() {
        return cn.jualn.miniapp.module.points.PointsProblems.unavailable(cn.jualn.miniapp.module.points.PointsProblems.Dependency.PRIVATE_MEDIA);
    }
}
