package cn.jualn.miniapp.module.admin.operation.service;

import cn.jualn.miniapp.module.authorization.service.CurrentAuthorization;
import cn.jualn.miniapp.module.authorization.service.RbacCursorCodec;
import cn.jualn.miniapp.module.authorization.model.RbacModels;

import cn.jualn.miniapp.module.admin.operation.bo.AdminAuditBO.*;
import cn.jualn.miniapp.module.admin.operation.mapper.AdminAuditMapper;
import cn.jualn.miniapp.common.exception.ContractProblemException;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class AdminAuditQueries {
    private final AdminAuditMapper mapper;
    private final CurrentAuthorization authorization;
    private final RbacCursorCodec cursors;
    private final ObjectMapper json;
    private final ObjectMapper cursorJson=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    public void root(long actor) {
        RbacModels.Decision decision;
        try {decision=authorization.current(actor);}
        catch(ContractProblemException failure) {if(failure.getStatus()==HttpStatus.SERVICE_UNAVAILABLE)throw unavailable();throw failure;}
        catch(org.springframework.dao.DataAccessException failure) {throw unavailable();}
        if(decision.grants().stream().noneMatch(g->decision.owns(g.assignmentId())))
            throw new ContractProblemException(HttpStatus.FORBIDDEN,"/problems/forbidden","Current ROOT required");
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Page page(Query q,String cursor) {
        root(q.actor());
        if(q.size()<1 || q.size()>100 || q.after()==null || q.before()==null || !q.before().isAfter(q.after())
                || Duration.between(q.after(),q.before()).compareTo(Duration.ofDays(31))>0) throw invalid();
        if("DUTY".equals(q.kind()) && ((q.person()==null)==(q.user()==null)
            || q.source()!=null && !SOURCES.contains(q.source()))) throw invalid();
        if(q.result()!=null && !Set.of("SUCCEEDED","REJECTED","FAILED","UNKNOWN").contains(q.result())) throw invalid();
        for(String id:java.util.Arrays.asList(q.person(),q.user(),q.actorUser(),q.targetId()))if(id!=null && (id.isBlank() || id.length()>128))throw invalid();
        if(q.operation()!=null && (q.operation().isBlank() || q.operation().length()>128) || q.targetType()!=null && (q.targetType().isBlank() || q.targetType().length()>64))throw invalid();
        if(cursor!=null && (cursor.isBlank() || cursor.length()>1024))throw invalidCursor();
        String binding=hash(q);
        long high;LocalDateTime lastTime=null;String last=null;
        if(cursor==null) high=mapper.highWater();
        else {
            try {
                var a=cursors.decode(cursor);
                if(!"ADMIN_AUDIT".equals(a.kind()) || a.actor()!=q.actor() || !binding.equals(a.binding())) throw invalidCursor();
                high=a.highOrder();lastTime=LocalDateTime.parse(a.lastTime());last=a.lastId();
            } catch(RuntimeException failure) {throw invalidCursor();}
        }
        var fetch=new Query(q.kind(),q.actor(),q.after(),q.before(),q.person(),q.user(),q.source(),q.actorUser(),q.operation(),q.targetType(),q.targetId(),q.result(),q.size()+1);
        var rows=mapper.page(fetch,high,lastTime,last);boolean more=rows.size()>q.size();rows=rows.stream().limit(q.size()).toList();
        String next=null;
        if(more) {var end=rows.get(rows.size()-1);next=cursors.encode(new RbacCursorCodec.Anchor(1,"ADMIN_AUDIT",q.actor(),binding,q.before().toString(),null,null,high,end.getRecordedAt().toString(),end.getId()));}
        List<JsonNode> items=new ArrayList<>();
        try {for(var row:rows)items.add(json.readTree(row.getPayload()));}
        catch(java.io.IOException failure) {throw unavailable();}
        var starts=mapper.coverage(q.kind()).stream().collect(java.util.stream.Collectors.toMap(c->c.source(),c->c.availableSince()));
        var sources="DUTY".equals(q.kind())?SOURCES:java.util.stream.Stream.concat(OPERATIONS.stream(),starts.keySet().stream()).distinct().sorted().toList();
        var coverage=sources.stream().map(source->{var since=starts.get(source);return new Coverage(source,since==null?"UNAVAILABLE":"PARTIAL",since==null?null:AdminAuditJournal.wire(since));}).toList();
        root(q.actor());return new Page(items,next,coverage);
    }
    private static final List<String> SOURCES=List.of("ROLE_ASSIGNMENT","DUTY","QUALIFICATION","SCOPE","DELEGATION","BINDING","MEMBERSHIP");
    // Known instrumented commands, not a claim of enabled permissions or complete historical coverage.
    private static final List<String> OPERATIONS=List.of(
        "grantUserRoleAssignment",
        "revokeUserRoleAssignment",
        "grantDelegationAuthority",
        "commitDutyChange",
        "createManagedPerson",
        "updateManagedPersonName",
        "changeManagedPersonLifecycle",
        "updatePersonMajorClassRegistration",
        "createPersonIdentifier",
        "changePersonIdentifier",
        "createUserPersonBinding",
        "reassociateUserPersonBinding",
        "endUserPersonBinding",
        "changePersonIdentity",
        "replacePersonMemberships",
        "changeOrganizationMemberFact",
        "grantPersonManagementScope",
        "revokePersonManagementScope",
        "createPersonOrganization",
        "updatePersonOrganization",
        "putManagedMentorProfile",
        "changeMentorQualification",
        "commitPartyRosterImport",
        "correctPartyRosterEntry",
        "muteAdminAccount",
        "banAdminAccount",
        "restoreAdminAccount",
        "resolveAdminReportCase",
        "actOnAdminManagedContent",
        "decideAdminSafetyReview",
        "createArticle",
        "replaceArticle",
        "publishArticle",
        "unpublishArticle",
        "createArticleCategory",
        "replaceArticleCategory",
        "createOfficialContent",
        "replaceOfficialContent",
        "publishOfficialContent",
        "unpublishOfficialContent",
        "createActivity",
        "replaceActivity",
        "publishActivity",
        "unpublishActivity",
        "cancelActivity",
        "endActivity",
        "createPublicEvent",
        "replacePublicEvent",
        "publishPublicEvent",
        "unpublishPublicEvent",
        "cancelPublicEvent",
        "endPublicEvent",
        "createPostCategory",
        "editPostCategory",
        "createPostTopic",
        "editPostTopic",
        "createLearningTask",
        "updateLearningTask",
        "publishLearningTask",
        "addLearningTaskAssignments",
        "adjustLearningTaskDeadline",
        "cancelLearningTask",
        "closeLearningTask",
        "revokeLearningTaskAssignment",
        "reviewLearningTaskSubmission",
        "reviewPointApplication",
        "reversePointGrant",
        "decidePointAcceptance");
    private String hash(Query q) {
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(cursorJson.writeValueAsBytes(q)));}
        catch(java.io.IOException|java.security.NoSuchAlgorithmException failure) {throw new IllegalStateException(failure);}
    }
    public static ContractProblemException unavailable() {return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,"/problems/admin-audit-unavailable","Audit source unavailable");}
    public static ContractProblemException invalid() {return ContractProblemException.validation(new ContractProblemException.Violation("query","/","INVALID_VALUE","Invalid audit query"));}
    private ContractProblemException invalidCursor() {return new ContractProblemException(HttpStatus.BAD_REQUEST,"/problems/invalid-cursor","Restart the audit query");}
}
