package cn.jualn.miniapp.module.authorization.web;

import cn.jualn.miniapp.module.authorization.support.RbacProblems;
import cn.jualn.miniapp.module.authorization.service.RbacQueryService;
import cn.jualn.miniapp.module.authorization.support.RbacTime;

import cn.jualn.miniapp.module.admin.auth.service.AdminTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import static cn.jualn.miniapp.module.authorization.web.RbacHttpModels.*;

@RestController @RequiredArgsConstructor
@RequestMapping(value="/v1/admin/authorization",produces=MediaType.APPLICATION_JSON_VALUE)
public class RbacController {
    private final AdminTokenService tokens;
    private final RbacQueryService queries;
    private final RbacHttpConverter converter;
    @io.swagger.v3.oas.annotations.Operation(operationId="listAuthorizationPermissions")
    @GetMapping("/permissions") public ResponseEntity<PermissionCatalog> permissions() {return ok(converter.permissions(queries.catalog(actor())));}
    @io.swagger.v3.oas.annotations.Operation(operationId="listAuthorizationRoles")
    @GetMapping("/roles") public ResponseEntity<RoleCatalog> roles() {return ok(converter.roles(queries.catalog(actor())));}
    @io.swagger.v3.oas.annotations.Operation(operationId="getAuthorizationRole")
    @GetMapping("/roles/{roleCode}") public ResponseEntity<Role> role(@PathVariable String roleCode) {return ok(converter.role(queries.role(actor(),roleCode)));}
    @io.swagger.v3.oas.annotations.Operation(operationId="listUserRoleAssignments")
    @GetMapping("/users/{userId}/role-assignments")
    public ResponseEntity<AssignmentPage> assignments(@PathVariable String userId,
            @RequestParam(required=false) String status,@RequestParam(required=false) String roleCode,
            @RequestParam(required=false) String namespace,@RequestParam(defaultValue="20") int pageSize,
            @RequestParam(required=false) String cursor,HttpServletRequest request) {
        long actor=actor();long target=queries.resolveUser(actor,userId,"authorization:read");
        query(request,Set.of("status","roleCode","namespace","pageSize","cursor"));
        return ok(converter.assignments(queries.assignments(actor,target,new RbacQueryService.AssignmentQuery(status,roleCode,namespace,pageSize),cursor)));
    }
    @io.swagger.v3.oas.annotations.Operation(operationId="getUserRoleAssignment")
    @GetMapping("/users/{userId}/role-assignments/{assignmentId}")
    public ResponseEntity<AssignmentDetail> assignment(@PathVariable String userId,@PathVariable String assignmentId) {
        long actor=actor();long target=queries.resolveUser(actor,userId,"authorization:read");
        return ok(converter.detail(queries.assignment(actor,target,assignmentId)));
    }
    @io.swagger.v3.oas.annotations.Operation(operationId="getMyAdminCapabilities")
    @GetMapping("/me/capabilities") public ResponseEntity<Capabilities> capabilities() {return ok(converter.capabilities(queries.capabilities(actor())));}
    @io.swagger.v3.oas.annotations.Operation(operationId="listAuthorizationChanges")
    @GetMapping("/changes")
    public ResponseEntity<AuditPage> changes(@RequestParam(required=false) String actorUserId,@RequestParam(required=false) String targetUserId,
            @RequestParam(required=false) String assignmentId,@RequestParam(required=false) String action,
            @RequestParam(required=false) String occurredAfter,@RequestParam(required=false) String occurredBefore,
            @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String cursor,HttpServletRequest request) {
        long actor=actor();queries.root(actor,"audit-log:read");
        query(request,Set.of("actorUserId","targetUserId","assignmentId","action","occurredAfter","occurredBefore","pageSize","cursor"));
        try {RbacTime.range(occurredAfter,occurredBefore);}
        catch(cn.jualn.miniapp.common.exception.ContractProblemException e) {throw RbacProblems.invalidQuery("date-time");}
        var q=new RbacQueryService.AuditQuery(filterId(actorUserId),filterId(targetUserId),assignmentId,action,
                boundary(occurredAfter,"occurredAfter"),boundary(occurredBefore,"occurredBefore"),pageSize);
        return ok(converter.audit(queries.changes(actor,q,cursor)));
    }
    private Long filterId(String value) {
        if(value==null) return null;
        try {return value.matches("[1-9][0-9]*")?Long.parseLong(value):-1L;} catch(NumberFormatException e) {return -1L;}
    }
    private java.time.LocalDateTime boundary(String value,String field) {
        try {return RbacTime.queryBoundary(value);}
        catch(cn.jualn.miniapp.common.exception.ContractProblemException e) {throw RbacProblems.invalidQuery(field);}
    }
    private void query(HttpServletRequest request,Set<String> allowed) {
        request.getParameterMap().forEach((name,values)->{
            if(!allowed.contains(name) || values.length!=1 || values[0].isEmpty()) throw RbacProblems.invalidQuery("query");
        });
    }
    private long actor() {return RbacHttpAccess.authenticate(tokens);}
    private <T> ResponseEntity<T> ok(T body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
