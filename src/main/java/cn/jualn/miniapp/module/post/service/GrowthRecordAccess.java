package cn.jualn.miniapp.module.post.service;

import cn.jualn.miniapp.module.post.GrowthProblems;
import cn.jualn.miniapp.module.post.bo.GrowthRecordBO.Link;
import cn.jualn.miniapp.module.post.entity.Post;
import cn.jualn.miniapp.module.post.mapper.GrowthRecordMapper;
import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;
import java.util.*;

/** Mandatory association lookup; a failed lookup is never an ordinary public Post. */
@Service @RequiredArgsConstructor
public class GrowthRecordAccess {
    private final GrowthRecordMapper links;
    private final cn.jualn.miniapp.module.post.mapper.PostMapper posts;
    private final GrowthQualification qualification;
    private final CurrentAuthorization authorization;
    private final cn.jualn.miniapp.module.user.service.UserAccountService accounts;
    @org.springframework.beans.factory.annotation.Autowired private cn.jualn.miniapp.module.authorization.service.BusinessAuthorizationLease lease;
    public Link link(long id) {try{return links.find(id);}catch(DataAccessException e){throw GrowthProblems.source();}}
    public boolean mayRead(long id,Long actor,boolean interaction){var post=posts.selectById(id);return post!=null && readable(post,actor,interaction);}
    public boolean safeVersion(long id){var post=posts.selectById(id);return post!=null && PostPublicAccess.visible(post,java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")));}
    public boolean readable(Post post,Long actor,boolean interaction) {
        var link=link(post.getId());if(link==null)return false;
        if(actor==null || post.getDeletedAt()!=null)return false;
        accounts.assertLoginAllowed(actor);
        String person=qualification.currentPerson(actor);
        if(person==null)return false;
        if(!interaction && link.personId().equals(person))return true;
        return PostPublicAccess.visible(post,java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))) && qualification.member(actor,link.organizationId());
    }
    public void original(Post post,long actor,boolean publishing) {
        accounts.assertLoginAllowed(actor);
        var link=post==null?null:link(post.getId());
        if(link==null || post.getDeletedAt()!=null || !Objects.equals(actor,post.getUserId()) || !link.personId().equals(qualification.currentPerson(actor)))throw GrowthProblems.missing();
        if(publishing && !link.personId().equals(qualification.requirePublisher(actor,link.organizationId(),link.recordType())))throw GrowthProblems.forbidden();
    }
    public boolean allowedAdmin(long post,long actor,String duty,String...permissions) {
        var link=link(post);if(link==null)return false;
        var d=authorization.current(actor);
        return allowed(d,link,duty,permissions);
    }
    public boolean allowed(RbacModels.Decision d,Link link,String duty,String...permissions) {
        return d.accountEligible() && d.source()==RbacModels.Source.ASSIGNMENT && (d.managementOwner("REPORT_HANDLE".equals(duty)?"REPORT":"POST", permissions) != null || d.grants().stream().anyMatch(g->
            duty.equals(g.dutyCode()) && g.businessTarget()!=null && "ORGANIZATION".equals(g.businessTarget().type())
            && link.organizationId().equals(g.businessTarget().organizationId()) && ("REPORT_HANDLE".equals(duty)?"REPORT":"POST").equals(g.namespace())
            && Arrays.stream(permissions).allMatch(g.permissionCodes()::contains)));
    }
    public void requireAdmin(long post,long actor,String duty,String...permissions) {
        var link=link(post);if(link==null)throw GrowthProblems.missing();
        java.util.function.Consumer<RbacModels.Decision> check=d->{if(!allowed(d,link,duty,permissions))throw GrowthProblems.missing();};
        if(lease==null)check.accept(authorization.current(actor));else lease.require(actor,check);
    }
    public void guard(long actor){qualification.guard(actor);}
    public static boolean growthFilter(String type,String organization) {
        if(type!=null && !Set.of("NONE","ALL","GROWTH","POLITICAL_BIRTHDAY").contains(type))throw cn.jualn.miniapp.module.post.PostProblems.invalid("growthType","INVALID_VALUE","Unsupported growth filter");
        boolean growth=type!=null && !"NONE".equals(type);
        if(organization!=null && !growth)throw cn.jualn.miniapp.module.post.PostProblems.invalid("growthOrganizationId","INVALID_VALUE","An explicit growthType is required");
        return growth;
    }
    public List<String> organizations(long actor,String duty,String permission) {
        var d=authorization.current(actor);if(!d.accountEligible() || d.source()!=RbacModels.Source.ASSIGNMENT)return List.of();
        if (d.managementOwner("REPORT_HANDLE".equals(duty)?"REPORT":"POST", permission) != null) return links.organizations(null).stream().sorted().toList();
        return d.grants().stream().filter(g->duty.equals(g.dutyCode()) && g.businessTarget()!=null && "ORGANIZATION".equals(g.businessTarget().type()) && g.permissionCodes().contains(permission)).map(g->g.businessTarget().organizationId()).distinct().sorted().toList();
    }
}
