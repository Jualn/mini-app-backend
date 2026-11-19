package cn.jualn.miniapp.module.content.service;

import cn.jualn.miniapp.module.content.mapper.GrowthContentMapper;
import cn.jualn.miniapp.module.post.service.GrowthRecordAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class GrowthManagementTargets {
    private final GrowthContentMapper mapper;
    private final GrowthRecordAccess access;
    public java.util.Map<String,cn.jualn.miniapp.module.content.bo.ManagementTarget> locations(java.util.List<cn.jualn.miniapp.module.content.bo.ManagementTarget.Reference> references) {
        if(references.isEmpty())return java.util.Map.of();
        try {
            var result=new java.util.HashMap<String,cn.jualn.miniapp.module.content.bo.ManagementTarget>();
            for(var r:mapper.locations(references)) {
                if(r.namespace()==null)throw cn.jualn.miniapp.module.post.GrowthProblems.source();
                result.put(r.type()+":"+r.id(),new cn.jualn.miniapp.module.content.bo.ManagementTarget(r.namespace(),
                    new cn.jualn.miniapp.module.content.bo.ManagementTarget.BusinessTarget(r.organizationId()==null?"PLATFORM":"ORGANIZATION",r.organizationId())));
            }
            if(result.size()!=references.stream().distinct().count())throw cn.jualn.miniapp.module.post.GrowthProblems.source();
            return java.util.Map.copyOf(result);
        }catch(org.springframework.dao.DataAccessException failure){throw cn.jualn.miniapp.module.post.GrowthProblems.source();}
    }
    public cn.jualn.miniapp.module.content.bo.ManagementTarget location(String type,long id) {
        return locations(java.util.List.of(new cn.jualn.miniapp.module.content.bo.ManagementTarget.Reference(type,id))).get(type+":"+id);
    }
    public Long root(String type,long id){try{return mapper.root(type,id);}catch(org.springframework.dao.DataAccessException failure){throw cn.jualn.miniapp.module.post.GrowthProblems.source();}}
    public boolean require(String type,long id,long actor,String duty,String...permissions){var root=root(type,id);if(root==null)return false;access.requireAdmin(root,actor,duty,permissions);return true;}
    public cn.jualn.miniapp.module.post.bo.GrowthRecordBO.Link link(String type,long id){var root=root(type,id);return root==null?null:access.link(root);}
}
