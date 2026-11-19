package cn.jualn.miniapp.module.article.service;

import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.common.security.AdminStpUtil;
import cn.jualn.miniapp.module.article.bo.ArticleSaveBO;
import cn.jualn.miniapp.module.article.entity.Article;
import cn.jualn.miniapp.module.article.mapper.ArticleMapper;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Current ROOT management capability or one same-source organizational duty; never membership or a generic GLOBAL role.
 * Lock order: Person gate -> RBAC governance/subjects -> Article guard/row.
 * Remote safety checks run between prepare and the independently revalidated writer. */
@Service @RequiredArgsConstructor
public class ArticleOrganizationPolicy {
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;

    private final PersonBusinessScopes persons;
    private final TransactionalAuthorization authorization;
    private final UnifiedDutyReader duties;
    private final ArticleMapper articles;
    public record Prepared(long actor,String organizationId,String name,boolean freeze,List<PersonBusinessScopes.Scope> sources,String rootAssignmentId) {
        public Prepared(long actor,String organizationId,String name,boolean freeze,List<PersonBusinessScopes.Scope> sources) {
            this(actor,organizationId,name,freeze,sources,null);
        }
    }
    public record Candidate(String organizationId,String name,String status) {}
    public record Candidates(List<Candidate> items,String nextCursor) {}

    public static ContractProblemException unavailable() {return new ContractProblemException(HttpStatus.SERVICE_UNAVAILABLE,"/problems/article-organization-authorization-unavailable","Trusted Article organization authorization is unavailable");}
    public static ContractProblemException migration() {return ContractProblemException.conflict("article-publisher-migration-required","Approved publisher mapping and historical signature evidence are required");}
    public long beginDeletion() {
        try {return begin("article:edit");}
        catch(ContractProblemException failure) {
            if(failure.getStatus()!=HttpStatus.FORBIDDEN) throw failure;
            long actor=begin("article:publish");require(actor,"article:unpublish");return actor;
        }
    }
    public long begin(String permission) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        AdminStpUtil.STP_LOGIC.checkLogin();long actor=AdminStpUtil.STP_LOGIC.getLoginIdAsLong();
        try {authorization.begin(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.SHARED,actor);require(actor,permission);return actor;}
        catch(org.springframework.dao.DataAccessException e){throw unavailable();}
        catch(ContractProblemException e){throw translate(e);}
    }
    public void require(long actor,String permission) {
        try {if(!authorization.currentNow(actor).allows(permission,"ARTICLE")) throw new ContractProblemException(HttpStatus.FORBIDDEN,"/problems/forbidden","Current Article capability is required");}
        catch(org.springframework.dao.DataAccessException e){throw unavailable();}
        catch(ContractProblemException e){throw translate(e);}
    }
    private ContractProblemException translate(ContractProblemException e) {return e.getStatus()==HttpStatus.SERVICE_UNAVAILABLE?unavailable():e;}
    private List<PersonBusinessScopes.Scope> sources(long actor,String duty) {
        try {return duties.effective(actor,duty,authorization.now());}
        catch(org.springframework.dao.DataAccessException e){throw unavailable();}
        catch(ContractProblemException e){throw translate(e);}
    }
    private String target(PersonBusinessScopes.Scope source) {
        var selection=duties.decode(source.selection(),UnifiedModels.Selection.class);
        var t=selection.target();
        if(t==null || !"ORGANIZATION".equals(t.type()) || t.organizationId()==null || t.personId()!=null || selection.audienceTargets()!=null) throw unavailable();
        return t.organizationId();
    }
    private List<PersonBusinessScopes.Scope> covering(long actor,String duty,String org) {
        return sources(actor,duty).stream().filter(s->org.equals(target(s))).toList();
    }
    private String rootOwner(long actor,String duty) {
        return duties.managementOwner(authorization.currentNow(actor),duty);
    }
    private List<PersonBusinessScopes.Scope> need(long actor,String duty,String org) {
        if(rootOwner(actor,duty)!=null) return List.of();
        var proof=covering(actor,duty,org);if(proof.isEmpty()) ArticleWriter.require(null);return proof;
    }
    public String resolve(ArticleSaveBO command) {
        if((command.publisherId()==null)==(command.publisherOrgId()==null)) ArticleBodyCodec.invalid("#/publisherOrgId","Exactly one publisher ID namespace is required");
        if(command.publisherOrgId()!=null) return command.publisherOrgId();
        String org=command.publisherId()==null?null:articles.mappedOrganization(command.publisherId());
        if(org==null) throw migration();return org;
    }
    private PersonBusinessScopes.OrganizationFact organization(String id) {
        try {return persons.organizationFact(id);}catch(org.springframework.dao.DataAccessException e){throw unavailable();}
        catch(ContractProblemException e){throw translate(e);}
    }
    private List<Candidate> active(long actor) {
        var ids=new TreeSet<String>();
        if(rootOwner(actor,"ARTICLE_EDIT")!=null) ids.addAll(persons.organizations());
        else sources(actor,"ARTICLE_EDIT").forEach(s->ids.add(target(s)));
        var result=new ArrayList<Candidate>();
        for(String id:ids) {var org=organization(id);if(org==null) throw unavailable();if("ACTIVE".equals(org.status())) result.add(new Candidate(id,org.name(),org.status()));}
        return List.copyOf(result);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Prepared prepare(String permission,Long id,ArticleSaveBO command,boolean publish) {
        return check(begin(permission),permission,id==null?null:ArticleWriter.require(articles.selectById(id)),command,publish);
    }
    public Prepared check(long actor,String permission,Article original,ArticleSaveBO command,boolean publish) {
        require(actor,permission);var proofs=new ArrayList<PersonBusinessScopes.Scope>();
        if(original!=null) {
            if(!organizationRepresentation(original)) throw migration();
            proofs.addAll(need(actor,"article:edit".equals(permission)?"ARTICLE_EDIT":"ARTICLE_PUBLISH",original.getPublisherOrgId()));
        }
        String org=command==null?original.getPublisherOrgId():resolve(command);
        boolean changing=original==null || !org.equals(original.getPublisherOrgId());
        if(command!=null) {
            var edit=covering(actor,"ARTICLE_EDIT",org);
            if(edit.isEmpty() && rootOwner(actor,"ARTICLE_EDIT")==null) {
                if(changing && active(actor).isEmpty()) throw ContractProblemException.conflict("no-authorized-publisher-organization","No authorized active publisher organization");
                ArticleWriter.require(null);
            }
            proofs.addAll(edit);
        }
        if(publish) {
            require(actor,"article:publish");
            if(original!=null) proofs.addAll(need(actor,"ARTICLE_PUBLISH",original.getPublisherOrgId()));
            proofs.addAll(need(actor,"ARTICLE_PUBLISH",org));
        }
        var fact=organization(org);if(fact==null) throw unavailable();
        if((changing || publish) && !"ACTIVE".equals(fact.status())) throw ContractProblemException.conflict("article-publisher-organization-inactive","Publisher organization is inactive");
        boolean freeze=publish && (original==null || !"PUBLISHED".equals(original.getPublishStatus()) || changing);
        return new Prepared(actor,org,fact.name(),freeze,proofs.stream().distinct().toList(),authorization.currentNow(actor).managementOwner("ARTICLE",permission));
    }
    public boolean organizationRepresentation(Article row) {
        if(row.getPublisherOrgId()==null || (row.getPublishedAt()!=null && row.getPublisherNameSnapshot()==null)) return false;
        if(articles.organizationAudited(row.getId())>0) return true;
        if(row.getPublisherId()==null || !row.getPublisherOrgId().equals(articles.mappedOrganization(row.getPublisherId()))) return false;
        return row.getPublishedAt()==null || articles.approvedSignature(row.getId(),row.getPublisherOrgId(),row.getPublisherNameSnapshot())==1;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public List<String> mediaOrganizations() {return readable(begin("media:read"));}
    public void requireReadOrganization(long actor,String org) {
        if(!readable(actor).contains(org) || organization(org)==null) ArticleWriter.require(null);
    }
    public List<String> readable(long actor) {
        if(authorization.currentNow(actor).managementOwner("ARTICLE","article:read")!=null) return persons.allOrganizations();
        var ids=new TreeSet<String>();sources(actor,"ARTICLE_EDIT").forEach(s->ids.add(target(s)));sources(actor,"ARTICLE_PUBLISH").forEach(s->ids.add(target(s)));return List.copyOf(ids);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.REPEATABLE_READ)
    public Candidates candidates(int size,String cursor) {
        if(size<1 || size>50) ArticleBodyCodec.invalidQuery("#/pageSize","pageSize must be 1..50");
        long actor=begin("article:edit");var rows=active(actor);var decision=authorization.currentNow(actor);
        String binding;
        try {binding=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((actor+"/"+decision.authorizationVersion()+"/"+decision.catalogVersion()+"/"+persons.epoch()+"/"+duties.encode(sources(actor,"ARTICLE_EDIT"))+"/"+duties.encode(rows)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        String after=ArticleCursor.decode(cursor,"organizations/"+binding);
        var remaining=rows.stream().filter(c->after==null || c.organizationId().compareTo(after)>0).toList();
        boolean more=remaining.size()>size;var page=more?remaining.subList(0,size):remaining;
        return new Candidates(page,more?ArticleCursor.encode(page.get(page.size()-1).organizationId(),"organizations/"+binding):null);
    }
    private String operationPermission(String operation,Article row) {
        if(Set.of("DELETE","RESTORE").contains(operation)) {
            if(row.getPublishedAt()==null && "DRAFT".equals(row.getPublishStatus())) return "article:edit";
            return "article:publish";
        }
        return "UNPUBLISH".equals(operation)?"article:unpublish":"PUBLISH".equals(operation)?"article:publish":"article:edit";
    }
    public void audit(Article before,Article after,String operation,Prepared proof,String expectedName) {
        if(Set.of("DELETE","RESTORE").contains(operation) && after.getPublishedAt()!=null) require(proof.actor(),"article:unpublish");
        var current=check(proof.actor(),operationPermission(operation,after),before,
                Set.of("CREATE","REPLACE").contains(operation)?new ArticleSaveBO(after.getTitle(),after.getSummary(),after.getCategoryId(),null,after.getCoverAttachmentId(),null,after.getCommentsEnabled(),after.getPublisherOrgId()):null,
                "PUBLISH".equals(operation) || "PUBLISHED".equals(after.getPublishStatus()) && !"UNPUBLISH".equals(operation));
        if(current.freeze() && !Objects.equals(expectedName,current.name())) throw unavailable();
        try {if(articles.organizationAudit(UUID.randomUUID().toString(),after, before==null?null:before.getPublisherOrgId(),before==null?null:before.getPublisherNameSnapshot(),proof.actor(),operation,duties.encode(current.rootAssignmentId()==null?current.sources():List.of(Map.of("sourceType","ROOT_OWNER","rootAssignmentId",current.rootAssignmentId()))),authorization.now())!=1) throw unavailable();}catch(org.springframework.dao.DataAccessException e){throw unavailable();}
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    if(Set.of("DELETE","RESTORE").contains(operation) && after.getPublishedAt()!=null) require(proof.actor(),"article:unpublish");
                    var finalProof = check(proof.actor(), operationPermission(operation,after), before,
                        Set.of("CREATE", "REPLACE").contains(operation)
                            ? new ArticleSaveBO(after.getTitle(), after.getSummary(), after.getCategoryId(), null,
                                after.getCoverAttachmentId(), null, after.getCommentsEnabled(), after.getPublisherOrgId()) : null,
                        "PUBLISH".equals(operation) || "PUBLISHED".equals(after.getPublishStatus()) && !"UNPUBLISH".equals(operation));
                    if (finalProof.freeze() && !Objects.equals(expectedName, finalProof.name())) throw unavailable();
                }
            });
        managementAudit.operation(java.util.UUID.randomUUID().toString(),proof.actor(),switch(operation) {case "CREATE"->"createArticle";case "REPLACE"->"replaceArticle";case "PUBLISH"->"publishArticle";case "DELETE"->"deleteArticle";case "RESTORE"->"restoreArticle";default->"unpublishArticle";},"ARTICLE",String.valueOf(after.getId()),authorization.now());
    }
}
