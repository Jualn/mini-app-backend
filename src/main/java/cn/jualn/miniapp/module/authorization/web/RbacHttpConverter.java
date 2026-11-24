package cn.jualn.miniapp.module.authorization.web;

import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.service.RbacQueryService;
import cn.jualn.miniapp.module.authorization.support.RbacTime;

import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import static cn.jualn.miniapp.module.authorization.web.RbacHttpModels.*;

@Component
public class RbacHttpConverter {
    public PermissionCatalog permissions(RbacQueryService.CatalogBO c) {
        return new PermissionCatalog(c.version(),c.permissions().stream().map(p->{var a=p.availability();
            return new Permission(p.code(),p.description(),p.namespaces(),a.lifecycle(),a.readiness(),a.grantable(),a.unavailableReason());}).toList());
    }
    public Role role(RbacQueryService.RoleBO r) {var a=r.availability();return new Role(r.code(),r.name(),r.permissionCodes(),r.namespaces(),a.lifecycle(),a.readiness(),a.grantable(),a.unavailableReason());}
    public RoleCatalog roles(RbacQueryService.CatalogBO c) {return new RoleCatalog(c.version(),c.roles().stream().map(this::role).toList());}
    public Assignment assignment(RbacModels.Assignment a,LocalDateTime evaluated) {
        return new Assignment(a.getId(),a.getUserId().toString(),a.getRoleCode(),a.getNamespace(),new Scope(a.getScopeType()),
                RbacTime.wire(a.getGrantedAt()),RbacTime.wire(a.getExpiresAt()),a.status(evaluated),a.getVersion(),a.getGrantChangeId(),RbacTime.wire(a.getRevokedAt()),a.getRevokeChangeId(),a.getOriginKind());
    }
    public AssignmentPage assignments(RbacQueryService.AssignmentPageBO p) {
        return new AssignmentPage(Long.toString(p.userId()),p.authorizationVersion(),p.catalogVersion(),RbacTime.wire(p.evaluatedAt()),
                p.items().stream().map(a->assignment(a,p.evaluatedAt())).toList(),p.nextCursor());
    }
    public AssignmentDetail detail(RbacQueryService.AssignmentPageBO p) {
        return new AssignmentDetail(Long.toString(p.userId()),p.authorizationVersion(),p.catalogVersion(),RbacTime.wire(p.evaluatedAt()),assignment(p.items().get(0),p.evaluatedAt()));
    }
    public Capabilities capabilities(RbacQueryService.CapabilitiesBO c) {
        var d=c.decision();return new Capabilities(Long.toString(d.userId()),"ASSIGNMENT",d.canUseAdmin(),d.authorizationVersion(),d.catalogVersion(),
                RbacTime.wire(d.evaluatedAt()),RbacTime.wire(c.refreshAfter()),d.grants().stream().map(g->new CapabilityGrant(g.assignmentId(),g.roleCode(),g.namespace(),new Scope(g.scopeType()),g.permissionCodes(),RbacTime.wire(g.expiresAt()))).toList(),
                d.ownerCapabilities().stream().map(caps -> new RbacHttpModels.OwnerCapability(caps.rootAssignmentId(),caps.namespace(),new Scope(caps.scopeType()),caps.permissionCodes())).toList());
    }
    public AuditPage audit(RbacQueryService.AuditPageBO page) {
        return new AuditPage(page.items().stream().map(i->{var c=i.change();return new AuditEntry(c.getId(),c.getOperationId(),Long.toString(c.getActorUserId()),Long.toString(c.getTargetUserId()),c.getAction(),c.getAssignmentId(),RbacTime.wire(c.getOccurredAt()),c.getReason(),"SUCCEEDED",
                i.before()==null?null:assignment(i.before(),c.getOccurredAt()),assignment(i.after(),c.getOccurredAt()));}).toList(),page.nextCursor());
    }
}
