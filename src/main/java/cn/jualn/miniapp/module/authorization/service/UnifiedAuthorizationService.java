package cn.jualn.miniapp.module.authorization.service;

import static cn.jualn.miniapp.module.authorization.support.UnifiedProblems.*;

import cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal;
import cn.jualn.miniapp.module.authorization.mapper.RbacMapper;
import cn.jualn.miniapp.module.authorization.model.RbacModels;
import cn.jualn.miniapp.module.authorization.support.RbacProblems;
import cn.jualn.miniapp.module.authorization.support.RbacTime;
import cn.jualn.miniapp.module.authorization.mapper.UnifiedMapper;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;

import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;

import cn.jualn.miniapp.common.web.AdminQuery;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Selection;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Intent;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Commit;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.AuthorityRequest;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Source;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.EffectiveDuty;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.EffectiveDuties;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Option;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Options;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Impact;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Preview;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Receipt;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Authority;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.AuthorityPage;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.Binding;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.AuthorityRow;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.PreviewRow;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.CommandRow;
import static cn.jualn.miniapp.module.authorization.model.UnifiedModels.CursorRow;

/**
 * One MySQL owner transaction for existing RBAC effects, Person-owned scopes, provenance, audit and fixed receipts.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
public class UnifiedAuthorizationService {

    private final AdminAuditJournal managementAudit;
    private final UnifiedMapper mapper;
    private final RbacMapper rbac;
    private final RbacAuthorizationReader authorization;
    private final TransactionalAuthorization locks;
    private final RbacCatalog catalog;
    private final UnifiedDutyCatalog duties;
    private final UnifiedDutyReader reader;
    private final PersonBusinessScopes persons;

    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.person.service.access.DutyCandidateDirectory candidates;

    private record Context(long epoch, LocalDateTime at, RbacModels.Decision actor) {
    }

    private record Page<T>(List<T> items, String next) {
    }

    private Context open(long actor, boolean write) {
        long epoch = locks.begin(write ? PersonAuthorityGate.Access.EXCLUSIVE : cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.SHARED);
        var now = rbac.now();
        var decision = authorization.readCurrent(actor, now);
        if (!decision.accountEligible() || decision.source() != RbacModels.Source.ASSIGNMENT) throw forbidden();
        return new Context(epoch, now, decision);
    }

    private static final String NO_DUTY_DEADLINE = "9999-12-31T23:59:59Z";

    public Options options(long actor, int size, String cursor) {
        var c = open(actor, false);
        var options = new ArrayList<Option>();
        var root = root(c.actor());
        if (root != null) {
            for (var t : duties.templates())
                if (ready(t.dutyCode())) {
                    List<Target> targets = new ArrayList<>();
                    if(t.targetTypes().contains("PLATFORM"))targets.add(new Target("PLATFORM",null,null));
                    if(t.targetTypes().contains("ORGANIZATION"))targets.addAll(persons.organizations().stream()
                            .filter(id -> !"PARTY_ORG_PERSONNEL_MANAGE".equals(t.dutyCode()) || persons.isActivePartyOrganization(id))
                            .map(id->new Target("ORGANIZATION",null,id)).toList());
                    if (!targets.isEmpty())
                        options.add(new Option(t.dutyCode(), t.roleCode(), t.namespace(), catalog.rolePermissions(t.roleCode(), t.namespace()), targets,
                                NO_DUTY_DEADLINE, root.assignmentId(), null));
                }
        } else for (var a : mapper.authorities())
            if (a.getGranteeUserId() == actor && "ACTIVE".equals(reader.authorityStatus(a, c.at()))) {
                for (var s : reader.list(a.getCeilings(), Selection.class))
                    if (ready(s.dutyCode())) {
                        var t = duties.template(s.dutyCode());
                        options.add(new Option(t.dutyCode(), t.roleCode(), t.namespace(), catalog.rolePermissions(t.roleCode(), t.namespace()),
                                List.of(s.target()), s.expiresAt(), a.getId(), s.audienceTargets()));
                    }
            }
        options.sort(Comparator.comparing(Option::dutyCode).thenComparing(Option::authorityId).thenComparing(reader::encode));
        var p = page(actor, "options", options, size, cursor, c.at(), c.actor().catalogVersion());
        return new Options(RbacTime.wire(c.at()), c.actor().catalogVersion(), root != null, p.items(), p.next());
    }

    private record CandidateSource(Context context, AuthorityRow authority, List<Selection> ceilings) {}

    private CandidateSource candidateSource(long actor, String authority, String duty) {
        var c=open(actor,false);
        duties.template(duty);
        if (!ready(duty)) throw unavailable();
        var root=root(c.actor());
        if (root!=null) {
            if (!root.assignmentId().equals(authority)) throw missing();
            return new CandidateSource(c,null,null);
        }
        var owned=mapper.authorities().stream().filter(a->a.getGranteeUserId()==actor
                && "ACTIVE".equals(reader.authorityStatus(a,c.at()))).toList();
        if(owned.isEmpty()) throw forbidden();
        var a=owned.stream().filter(x->x.getId().equals(authority)).findFirst().orElseThrow(cn.jualn.miniapp.module.authorization.support.UnifiedProblems::missing);
        var ceilings=reader.list(a.getCeilings(),Selection.class).stream().filter(x->duty.equals(x.dutyCode())).toList();
        if(ceilings.isEmpty()) throw missing();
        return new CandidateSource(c,a,ceilings);
    }

    public UnifiedModels.TargetOptions targetOptions(long actor,String authority,String duty,String type,String q,int size,String cursor) {
        candidateQuery(size,q);
        var source=candidateSource(actor,authority,duty);var c=source.context();var t=duties.template(duty);
        if(type!=null && !Set.of("PLATFORM","PERSON","ORGANIZATION").contains(type)) throw RbacProblems.invalidQuery("targetType");
        var types=t.targetTypes().stream().filter(x->type==null||type.equals(x)).sorted().toList();
        if(types.isEmpty()) throw RbacProblems.invalidQuery("targetType");
        var ceilings=source.ceilings()==null?null:source.ceilings().stream().map(x->new cn.jualn.miniapp.module.person.service.access.DutyCandidateDirectory.Ceiling(x.target().type(),x.target().personId(),x.target().organizationId())).toList();
        if(source.ceilings()!=null && !Set.of("POST_MODERATE","POST_SAFETY_REVIEW","REPORT_HANDLE").contains(duty) && source.ceilings().stream().anyMatch(x->"PLATFORM".equals(x.target().type())))ceilings=null;
        String binding=candidateBinding(source,List.of("targets",authority,duty,type==null?"":type,q==null?"":q,size));
        long offset=candidateOffset(actor,binding,cursor,c.at());
        var rows=candidates.targets(ceilings,types,"POINTS_CORRECTION".equals(duty),"PARTY_ORG_PERSONNEL_MANAGE".equals(duty),"MENTOR".equals(t.namespace()),
                catalog.role(t.roleCode()).name()+" · 平台",q,offset,size+1);
        boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);
        var items=rows.stream().map(x->new UnifiedModels.TargetOption(new Target(x.type(),x.personId(),x.organizationId()),
                "PLATFORM".equals(x.type())?catalog.role(t.roleCode()).name()+" · 平台":x.label(),x.distinguishingLabel())).toList();
        return new UnifiedModels.TargetOptions(RbacTime.wire(c.at()),c.actor().catalogVersion(),c.actor().authorizationVersion(),items,
                more?candidateNext(actor,binding,offset+size,source):null);
    }

    public UnifiedModels.RecipientOptions recipientOptions(long actor,String authority,String duty,Target target,String filter,String q,int size,String cursor) {
        candidateQuery(size,q);
        if(target==null || !Set.of("PLATFORM","PERSON","ORGANIZATION").contains(target.type())
                || ("PERSON".equals(target.type()) != (target.personId()!=null))
                || ("ORGANIZATION".equals(target.type()) != (target.organizationId()!=null))) throw RbacProblems.invalidQuery("targetType");
        var source=candidateSource(actor,authority,duty);var c=source.context();
        String until=source.authority()==null?"9999-12-31T23:59:59Z":RbacTime.wire(source.authority().getExpiresAt());
        var selection=new Selection(duty,target,until,null);
        if(!duties.template(duty).targetTypes().contains(target.type())) throw RbacProblems.invalidQuery("targetType");
        if(source.ceilings()!=null && source.ceilings().stream().noneMatch(x->Set.of("POST_MODERATE","POST_SAFETY_REVIEW","REPORT_HANDLE").contains(duty)?x.target().equals(target):targetCovers(x.target(),target))) throw missing();
        selection(selection,c.at(),false);
        boolean independent=source.authority()==null || "INDEPENDENT_APPROVAL".equals(source.authority().getRecipientRule());
        if(!independent && !"ORGANIZATION".equals(target.type())) throw missing();
        // A name/filter lookup must remain in the approved visible target set.
        if(filter!=null) {
            if(!"PLATFORM".equals(target.type()) && !("ORGANIZATION".equals(target.type()) && filter.equals(target.organizationId()))) throw missing();
            persons.requireOrganization(filter);
        }
        String binding=candidateBinding(source,java.util.Arrays.asList("recipients",authority,duty,target,filter,q,size));
        long offset=candidateOffset(actor,binding,cursor,c.at());
        var rows=candidates.recipients(independent,target.organizationId(),filter,q,c.at(),offset,size+1);
        boolean more=rows.size()>size;if(more)rows=rows.subList(0,size);
        List<String> visible=filter!=null?List.of(filter):"ORGANIZATION".equals(target.type())?List.of(target.organizationId()):null;
        var organizations=candidates.organizations(rows.stream().map(x->x.personId()).filter(Objects::nonNull).distinct().toList(),visible,c.at());
        var accountIds=rows.stream().map(x->x.userId()).filter(Objects::nonNull).distinct().toList();
        var accounts=accountIds.isEmpty()?java.util.Map.<Long,RbacModels.Subject>of():rbac.subjects(accountIds).stream()
                .collect(java.util.stream.Collectors.toMap(RbacModels.Subject::getUserId,java.util.function.Function.identity()));
        if(accounts.size()!=accountIds.size() || accounts.values().stream().anyMatch(a->!"ASSIGNMENT".equals(a.getSource()) || a.getVersion()==null || a.getVersion().isBlank()))throw unavailable();
        var items=new ArrayList<UnifiedModels.RecipientOption>();
        for(var row:rows) {
            var person=row.personId()==null?null:new UnifiedModels.RecipientPerson(row.personId(),row.realName(),organizations.stream()
                    .filter(o->row.personId().equals(o.personId())).limit(20)
                    .map(o->new UnifiedModels.RecipientOrganization(o.organizationId(),o.name(),o.distinguishingLabel())).toList());
            String state=row.userId()==null?"PERSON_UNBOUND":person==null?"ACCOUNT_ONLY":"BOUND";
            String reason=row.userId()==null?"PERSON_UNBOUND":!RbacAuthorizationReader.accountEligible(accounts.get(row.userId()),c.at())?"ACCOUNT_BLOCKED":row.userId()==actor
                    || row.personId()!=null && !"ACTIVE".equals(row.personStatus())?"RECIPIENT_INELIGIBLE":"NONE";
            items.add(new UnifiedModels.RecipientOption(row.candidateId(),state,row.userId()==null?null:row.userId().toString(),row.nickname(),person,"NONE".equals(reason),reason));
        }
        return new UnifiedModels.RecipientOptions(RbacTime.wire(c.at()),c.actor().catalogVersion(),c.actor().authorizationVersion(),items,
                more?candidateNext(actor,binding,offset+size,source):null);
    }

    private static void candidateQuery(int size,String q) {
        if(size<1 || size>50) throw RbacProblems.invalidQuery("pageSize");
        if(q!=null && (q.isBlank() || q.length()>100)) throw RbacProblems.invalidQuery("q");
    }
    private String candidateBinding(CandidateSource s,Object request) {
        return hash(java.util.Arrays.asList(request,s.context().epoch(),s.context().actor().authorizationVersion(),
                s.context().actor().catalogVersion(),s.authority(),s.ceilings(),rbac.candidateDirectoryVersion()));
    }
    private long candidateOffset(long actor,String binding,String cursor,LocalDateTime at) {
        if(cursor==null)return 0;
        var value=mapper.cursor(cursor);
        if(value==null || value.actor()!=actor || !binding.equals(value.binding()) || !value.expiresAt().isAfter(at))
            throw problem(HttpStatus.BAD_REQUEST,"invalid-cursor");
        return value.itemOffset();
    }
    private String candidateNext(long actor,String binding,long offset,CandidateSource source) {
        var c=source.context();var until=c.at().plusMinutes(5);var boundary=candidates.nextBoundary(c.at());
        if(boundary!=null && boundary.isBefore(until))until=boundary;
        if(source.authority()!=null && source.authority().getExpiresAt().isBefore(until))until=source.authority().getExpiresAt();
        String id=id();one(mapper.insertCursor(new CursorRow(id,actor,binding,offset,until)));return id;
    }

    public EffectiveDuties effective(long actor, long user, int size, String cursor) {
        var c = open(actor, false);
        var rows = persons.forUser(user);
        if (actor != user && root(c.actor()) == null)
            rows = rows.stream().filter(s -> canGovern(actor, s, c.at())).toList();
        if (actor != user && root(c.actor()) == null && rows.isEmpty()) throw missing();
        var target = target(user);
        var items = rows.stream().map(s -> effective(s, c.at())).sorted(Comparator.comparing(EffectiveDuty::dutyCode).thenComparing(e -> e.source().scopeGrantIds().get(0))).toList();
        var p = page(actor, "effective:" + user, items, size, cursor, c.at(), c.actor().catalogVersion() + target.getVersion());
        return new EffectiveDuties(Long.toString(user), target.getVersion(), c.actor().catalogVersion(), RbacTime.wire(c.at()), p.items(), p.next());
    }

    public UnifiedModels.DutySummary dutySummary(long actor, List<String> requested) {
        AdminQuery.validate(requested, "dutyCode", 32);
        for (var code : requested) {
            if (duties.templates().stream().noneMatch(template -> template.dutyCode().equals(code))) {
                throw AdminQuery.invalid("dutyCode");
            }
        }
        try {
            // The gate, subject, directory versions and proofs all share this transaction snapshot.
            persons.lock(false);
            var at = rbac.now();
            var subject = rbac.subject(actor);
            if (subject == null || !RbacAuthorizationReader.accountEligible(subject, at)) {
                throw new ContractProblemException(HttpStatus.UNAUTHORIZED, "/problems/admin-authentication-required", "Management session required");
            }
            if (subject.getSource() == null || !Set.of("ASSIGNMENT", "LEGACY").contains(subject.getSource())) {
                throw AdminQuery.unavailable();
            }
            var permissions = rbac.permissions();
            var roles = rbac.roles();
            catalog.verify(permissions, roles, rbac.members());
            var version = reader.runtimeVersion(catalog.runtimeVersion(permissions, roles));
            var rows = persons.forUser(actor);
            var currentDecision = authorization.readCurrent(actor, at);
            var refresh = at.plusSeconds(60);
            var items = new ArrayList<UnifiedModels.DutySummaryItem>();
            for (var code : requested) {
                boolean effective = reader.managementOwner(currentDecision, code) != null;
                if (duties.supported(code)) {
                    for (var scope : rows) {
                        if (code.equals(scope.dutyCode())) {
                            // Evaluate every relevant source: a true item must not hide another unknown source.
                            boolean current = "EFFECTIVE".equals(reader.reason(scope, at));
                            effective = effective || current;
                            refresh = reader.refreshBoundary(scope, at, refresh);
                        }
                    }
                }
                items.add(new UnifiedModels.DutySummaryItem(code, effective));
            }
            if (subject.getVersion() == null || subject.getVersion().isBlank() || !refresh.isAfter(rbac.now())) {
                throw AdminQuery.unavailable();
            }
            return new UnifiedModels.DutySummary(Long.toString(actor), subject.getVersion(), version,
                    RbacTime.wire(at), RbacTime.wire(refresh), List.copyOf(items));
        } catch (RuntimeException failure) {
            AdminQuery.rethrowAuthentication(failure);
            throw AdminQuery.unavailable();
        }
    }

    public AuthorityPage authorities(long actor, int size, String cursor) {
        var c = open(actor, false);
        boolean root = root(c.actor()) != null;
        var items = mapper.authorities().stream().filter(a -> root || a.getGranteeUserId() == actor || a.getGrantedBy() == actor)
                .map(a -> authority(a, c.at())).toList();
        var p = page(actor, "authorities", items, size, cursor, c.at(), c.actor().catalogVersion());
        return new AuthorityPage(p.items(), p.next());
    }

    public Authority grantAuthority(long actor, AuthorityRequest request, String key) {
        request = normalize(request);
        key(key);
        var c = open(actor, true);
        var root = requireRoot(c.actor());
        long user = user(request.granteeUserId());
        self(actor, user);
        locks.begin(cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate.Access.EXCLUSIVE, actor, user);
        target(user);
        managementAudit.confirmed(actor, "grantDelegationAuthority", "AUTHORIZATION", Long.toString(user));
        if (!reader.eligible(user, c.at())) throw forbidden();
        var prior = replay(actor, "grantDelegationAuthority", Long.toString(user), key, request, Authority.class);
        if (prior != null) return prior;
        var expires = expiry(request.expiresAt());
        future(expires, c.at());
        var bindings = new ArrayList<Binding>();
        for (var s : request.ceilings()) {
            selection(s, c.at(), true);
            if (expiry(s.expiresAt()).isAfter(expires)) throw RbacProblems.invalid("expiresAt");
            if ("SAME_ORGANIZATION_MEMBERS".equals(request.recipientRule())) {
                if (!"ORGANIZATION".equals(s.target().type())) throw forbidden();
                bind(bindings, actor, s.target().organizationId(), c.at());
                bind(bindings, user, s.target().organizationId(), c.at());
            }
        }
        if (!Set.of("SAME_ORGANIZATION_MEMBERS", "INDEPENDENT_APPROVAL").contains(request.recipientRule()))
            throw RbacProblems.invalid("recipientRule");
        var a = new AuthorityRow();
        a.setId(id());
        a.setGranteeUserId(user);
        a.setGrantedBy(actor);
        a.setRootAssignmentId(root.assignmentId());
        a.setCeilings(reader.encode(request.ceilings()));
        a.setMemberBindings(reader.encode(bindings.stream().distinct().toList()));
        a.setRecipientRule(request.recipientRule());
        a.setGrantedAt(c.at());
        a.setExpiresAt(expires);
        a.setReason(request.reason());
        a.setRevision(1);
        a.setChangeId(id());
        one(mapper.insertAuthority(a));
        var response = authorityWithoutProof(a, "ACTIVE");
        managementAudit.delegation(a, request.ceilings(), bindings, actor, a.getChangeId(), "GRANT", c.at());
        saveCommand(a.getChangeId(), actor, "grantDelegationAuthority", Long.toString(user), key, request, response, a, c.at());
        revise(user, c.at());
        persons.advance(c.epoch());
        var finalAt = rbac.now();
        requireRoot(authorization.readCurrent(actor, finalAt));
        future(expires, finalAt);
        if (!"ACTIVE".equals(reader.authorityStatus(a, finalAt))) throw unavailable();
        beforeCommit(() -> {
            var at = rbac.now();
            requireRoot(authorization.readCurrent(actor, at));
            future(expires, at);
            if (!"ACTIVE".equals(reader.authorityStatus(a, at))) throw unavailable();
        });
        return response;
    }

    public Preview preview(long actor, Intent intent, String key) {
        intent = normalize(intent);
        key(key);
        var c = open(actor, true);
        intent = resolveDeadlines(intent, c.actor(), c.at());
        authorize(actor, c.actor(), intent, c.at(), true);
        String target = intentTarget(intent);
        var prior = replay(actor, "previewDutyChange", target, key, intent, Preview.class);
        if (prior != null) return prior;
        var row = new PreviewRow();
        row.setId(id());
        row.setActor(actor);
        row.setIntentJson(reader.encode(intent));
        row.setSnapshotHash(snapshot(c.actor(), intent, c.at()));
        row.setCreatedAt(c.at());
        row.setExpiresAt(c.at().plusMinutes(5));
        one(mapper.insertPreview(row));
        var impacts = impacts(intent, c.at());
        var first = page(actor, "preview:" + row.getId(), impacts, 20, null, c.at(), row.getSnapshotHash());
        var result = preview(row, c.actor(), first);
        saveCommand(id(), actor, "previewDutyChange", target, key, intent, result, row, c.at());
        return result;
    }

    public Preview preview(long actor, String id, int size, String cursor) {
        var c = open(actor, false);
        var row = previewRow(actor, id);
        var intent = reader.decode(row.getIntentJson(), Intent.class);
        authorize(actor, c.actor(), intent, c.at(), false);
        fresh(row, c.actor(), intent, c.at());
        var p = page(actor, "preview:" + row.getId(), impacts(intent, c.at()), size, cursor, c.at(), row.getSnapshotHash());
        return preview(row, c.actor(), p);
    }

    public Receipt commit(long actor, Commit command, String key) {
        command = new Commit(command.previewId(), normalize(command.intent()));
        key(key);
        var c = open(actor, true);
        var intent = resolveDeadlines(command.intent(), c.actor(), c.at());
        command = new Commit(command.previewId(), intent);
        authorize(actor, c.actor(), intent, c.at(), false);
        var prior = replay(actor, "commitDutyChange", intentTarget(intent), key, command, Receipt.class);
        if (prior != null) return prior;
        var row = previewRow(actor, command.previewId());
        if (!hash(intent).equals(hash(reader.decode(row.getIntentJson(), Intent.class)))) throw stale();
        fresh(row, c.actor(), intent, c.at());
        authorize(actor, c.actor(), intent, c.at(), true);
        managementAudit.confirmed(actor, "commitDutyChange", "AUTHORIZATION", intentTarget(intent));
        String change = id();
        var assignments = new ArrayList<String>();
        var scopes = new ArrayList<String>();
        var audit = new ArrayList<PersonBusinessScopes.Scope>();
        long changedUser;
        if ("GRANT".equals(intent.kind()) || "REPLACE".equals(intent.kind())) {
            long user = user(intent.granteeUserId());
            changedUser = user;
            locks.begin(PersonAuthorityGate.Access.EXCLUSIVE, actor, user);
            revise(user, c.at());
            if ("REPLACE".equals(intent.kind())) revokeComplete(actor, persons.scope(intent.sourceId()), intent.reason(), change, c.at());
            var root = root(c.actor());
            AuthorityRow authority = root == null ? mapper.authority(intent.authorityId()) : null;
            for (var s : intent.items()) {
                var t = duties.template(s.dutyCode());
                var a = new RbacModels.Assignment();
                a.setId(id());
                a.setUserId(user);
                a.setRoleCode(t.roleCode());
                a.setNamespace(t.namespace());
                a.setScopeType("GLOBAL");
                a.setGrantedAt(c.at());
                a.setExpiresAt(expiry(s.expiresAt()));
                a.setVersion(id());
                a.setGrantChangeId(id());
                a.setOriginKind("UNIFIED");
                one(mapper.insertAssignment(a));
                rbacAudit(actor, a, null, change, c.at());
                assignments.add(a.getId());
                var bindings = new ArrayList<Binding>();
                if (authority != null && "SAME_ORGANIZATION_MEMBERS".equals(authority.getRecipientRule()))
                    bind(bindings, user, s.target().organizationId(), c.at());
                var scope = new PersonBusinessScopes.Scope(id(), user, s.dutyCode(), a.getId(), authority == null ? null : authority.getId(), root == null ? null : root.assignmentId(),
                        reader.encode(s), reader.encode(bindings), actor, c.at(), a.getExpiresAt(), change, null, null, null);
                persons.grant(scope);
                scopes.add(scope.id());
                audit.add(scope);
                managementAudit.unifiedScope(scope, s, bindings, actor, change, "GRANT", c.at());
            }
        } else {
            var affected = affected(intent);
            changedUser = sourceUser(intent);
            locks.begin(PersonAuthorityGate.Access.EXCLUSIVE, actor, changedUser);
            revise(changedUser, c.at());
            if ("DELEGATION_AUTHORITY".equals(intent.sourceType())) {
                var authority = mapper.authority(intent.sourceId());
                one(mapper.revokeAuthority(intent.sourceId(), actor, intent.reason(), c.at()));
                managementAudit.delegation(authority, reader.list(authority.getCeilings(), Selection.class), reader.list(authority.getMemberBindings(), Binding.class), actor, change, "REVOKE", c.at());
            } else if ("AUTHORIZATION".equals(intent.sourceType())) {
                assignments.add(persons.scope(intent.sourceId()).assignmentId());
                revokeComplete(actor, persons.scope(intent.sourceId()), intent.reason(), change, c.at());
            } else if ("SCOPE".equals(intent.sourceType()))
                persons.revoke(intent.sourceId(), actor, intent.reason(), c.at());
            else {
                var a = rbac.assignment(intent.sourceId());
                if (a == null) throw missing();
                var before = reader.decode(reader.encode(a), RbacModels.Assignment.class);
                String next = id(), revoke = id();
                one(rbac.revoke(a.getId(), a.getVersion(), next, revoke, c.at()));
                a.setVersion(next);
                a.setRevokedAt(c.at());
                a.setRevokeChangeId(revoke);
                rbacAudit(actor, a, before, change, c.at());
                assignments.add(a.getId());
            }
            for (var s : affected) {
                scopes.add(s.id());
                audit.add(s);
                if (!"AUTHORIZATION".equals(intent.sourceType()))
                    managementAudit.unifiedScope(s, reader.decode(s.selection(), Selection.class), reader.list(s.memberBindings(), Binding.class), actor, change,
                            "SCOPE".equals(intent.sourceType()) ? "REVOKE" : "INVALIDATE", c.at());
            }
        }
        var version = target(changedUser).getVersion();
        var response = new Receipt(change, version, "ATOMIC", RbacTime.wire(c.at()), List.copyOf(assignments), List.copyOf(scopes));
        saveCommand(change, actor, "commitDutyChange", intentTarget(intent), key, command, response, audit, c.at());
        persons.advance(c.epoch());
        var finalAt = rbac.now();
        var current = authorization.readCurrent(actor, finalAt);
        if (!current.accountEligible()) throw forbidden();
        // Effects may revoke the requested source, so final permission checks use the surviving ROOT or issuing authority.
        if (root(current) == null && ("GRANT".equals(intent.kind()) || "REPLACE".equals(intent.kind()))) authorize(actor, current, grantIntent(intent), finalAt, true);
        if ("GRANT".equals(intent.kind()) || "REPLACE".equals(intent.kind()))
            for (var s : audit) if (!"EFFECTIVE".equals(reader.reason(s, finalAt))) throw unavailable();
        var finalIntent = intent;
        beforeCommit(() -> {
            var at = rbac.now();
            var decision = authorization.readCurrent(actor, at);
            if (!decision.accountEligible()) throw forbidden();
            if (root(decision) == null && ("GRANT".equals(finalIntent.kind()) || "REPLACE".equals(finalIntent.kind())))
                authorize(actor, decision, grantIntent(finalIntent), at, true);
            if ("GRANT".equals(finalIntent.kind()) || "REPLACE".equals(finalIntent.kind()))
                for (var scope : audit) if (!"EFFECTIVE".equals(reader.reason(scope, at))) throw unavailable();
        });
        return response;
    }

    private void authorize(long actor, RbacModels.Decision decision, Intent intent, LocalDateTime at, boolean future) {
        if (intent == null) throw RbacProblems.invalid("intent");
        if ("REPLACE".equals(intent.kind())) {
            var old = persons.scope(intent.sourceId());
            if (old == null || old.granteeUserId() != user(intent.granteeUserId())) throw missing();
            authorize(actor, decision, new Intent("REVOKE", null, null, null, "AUTHORIZATION", intent.sourceId(), intent.reason()), at, future);
            authorize(actor, decision, grantIntent(intent), at, future);
            return;
        }

        if ("GRANT".equals(intent.kind())) {
            long user = user(intent.granteeUserId());
            self(actor, user);
            var root = root(decision);
            AuthorityRow a = null;
            if (root != null) {
                if (!root.assignmentId().equals(intent.authorityId())) throw forbidden();
            } else {
                a = mapper.authority(intent.authorityId());
                if (a == null || a.getGranteeUserId() != actor || !"ACTIVE".equals(reader.authorityStatus(a, at)))
                    throw forbidden();
            }
            target(user);
            if (!reader.eligible(user, at)) throw forbidden();
            if (intent.items() == null || intent.items().isEmpty() || intent.items().size() > 20)
                throw RbacProblems.invalid("items");
            for (var s : intent.items()) {
                selection(s, at, future);
                if (a != null) {
                    if (expiry(s.expiresAt()).isAfter(a.getExpiresAt()) || reader.list(a.getCeilings(), Selection.class).stream().noneMatch(x -> covers(x, s)))
                        throw forbidden();
                    if ("SAME_ORGANIZATION_MEMBERS".equals(a.getRecipientRule())) {
                        if (!"ORGANIZATION".equals(s.target().type())) throw forbidden();
                        var bindings = new ArrayList<Binding>();
                        bind(bindings, user, s.target().organizationId(), at);
                    }
                }
            }
        } else if ("REVOKE".equals(intent.kind())) {
            if ("DELEGATION_AUTHORITY".equals(intent.sourceType())) {
                requireRoot(decision);
                var a = mapper.authority(intent.sourceId());
                if (a == null) throw missing();
                self(actor, a.getGranteeUserId());
                if (future && a.getRevokedAt() != null) throw stale();
            } else {
                PersonBusinessScopes.Scope scope;
                if (Set.of("SCOPE", "AUTHORIZATION").contains(intent.sourceType())) scope = persons.scope(intent.sourceId());
                else if ("ASSIGNMENT".equals(intent.sourceType())) {
                    var a = rbac.assignment(intent.sourceId());
                    if (a == null) throw missing();
                    if (!"UNIFIED".equals(a.getOriginKind())) throw forbidden();
                    self(actor, a.getUserId());
                    if (future && !"ACTIVE".equals(a.status(at))) throw stale();
                    scope = persons.assignmentScope(a.getId());
                    if (scope == null) throw RbacProblems.unavailable();
                } else throw RbacProblems.invalid("sourceType");
                if (scope == null) throw missing();
                self(actor, scope.granteeUserId());
                if (root(decision) == null && !canGovern(actor, scope, at)) throw missing();
                if (future && scope.revokedAt() != null) throw stale();
            }
        } else throw RbacProblems.invalid("kind");
    }

    private boolean canGovern(long actor, PersonBusinessScopes.Scope s, LocalDateTime at) {
        if (s.grantedBy() != actor || s.authorityId() == null) return false;
        var a = mapper.authority(s.authorityId());
        return a != null && a.getGranteeUserId() == actor && "ACTIVE".equals(reader.authorityStatus(a, at))
                && reader.list(a.getCeilings(), Selection.class).stream().anyMatch(x -> covers(x, reader.decode(s.selection(), Selection.class)));
    }

    private void selection(Selection s, LocalDateTime at, boolean checkFuture) {
        var t = duties.template(s.dutyCode());
        if (!ready(s.dutyCode())) throw unavailable();
        if (s.target() == null || !t.targetTypes().contains(s.target().type())) throw RbacProblems.invalid("target");
        if ("PARTY_ORG_PERSONNEL_MANAGE".equals(s.dutyCode()) && "ORGANIZATION".equals(s.target().type())
                && !persons.isActivePartyOrganization(s.target().organizationId())) throw missing();
        if ("MENTOR".equals(t.namespace()) && "PERSON".equals(s.target().type())) persons.requireMentor(s.target().personId());
        if (checkFuture) future(expiry(s.expiresAt()), at);
        if (PlatformDutyPolicy.supports(s.dutyCode()) && !PlatformDutyPolicy.matches(t, s))
            throw RbacProblems.invalid("target");
        if("POINTS_CORRECTION".equals(s.dutyCode())) {
            // Correction targets retained historical ownership, including archived persons/inactive organizations.
            if("PERSON".equals(s.target().type()) && persons.lifecycle(s.target().personId())==null
                    || "ORGANIZATION".equals(s.target().type()) && persons.organizationFact(s.target().organizationId())==null) throw missing();
        } else checkTarget(s.target());
        if (!"LEARNING_MANAGE".equals(s.dutyCode()) && s.audienceTargets() != null)
            throw RbacProblems.invalid("audienceTargets");
        if (s.audienceTargets() != null) for (var target : s.audienceTargets()) checkTarget(target);
    }

    private void checkTarget(Target t) {
        if ("ORGANIZATION".equals(t.type())) persons.requireOrganization(t.organizationId());
        else if ("PERSON".equals(t.type())) {
            persons.requirePerson(t.personId());
        } else if (!"PLATFORM".equals(t.type())) throw RbacProblems.invalid("target");
    }

    private boolean covers(Selection ceiling, Selection requested) {
        if(java.util.Set.of("POST_MODERATE","POST_SAFETY_REVIEW","REPORT_HANDLE").contains(requested.dutyCode()) && !ceiling.target().equals(requested.target()))return false;
        if (!ceiling.dutyCode().equals(requested.dutyCode()) || !targetCovers(ceiling.target(), requested.target())
                || expiry(requested.expiresAt()).isAfter(expiry(ceiling.expiresAt()))) return false;
        if (!"LEARNING_MANAGE".equals(ceiling.dutyCode())) return requested.audienceTargets() == null;
        var allowed = ceiling.audienceTargets() == null ? List.of(ceiling.target()) : ceiling.audienceTargets();
        var wanted = requested.audienceTargets() == null ? List.of(requested.target()) : requested.audienceTargets();
        return wanted.stream().allMatch(t -> allowed.stream().anyMatch(a -> targetCovers(a, t)));
    }

    private static boolean targetCovers(Target outer, Target inner) {
        return "PLATFORM".equals(outer.type()) || outer.equals(inner);
    }

    private boolean ready(String code) {
        return duties.ready(code, rbac.permissions(), rbac.roles());
    }

    private static Intent grantIntent(Intent intent) {
        return new Intent("GRANT", intent.granteeUserId(), intent.items(), intent.authorityId(), null, null, intent.reason());
    }

    private void revokeComplete(long actor, PersonBusinessScopes.Scope scope, String reason, String change, LocalDateTime at) {
        if (scope == null) throw missing();
        var assignment = rbac.assignment(scope.assignmentId());
        var paired = assignment == null ? null : persons.assignmentScope(assignment.getId());
        if (assignment == null || paired == null || !"UNIFIED".equals(assignment.getOriginKind())
                || assignment.getUserId() != scope.granteeUserId()
                || !scope.id().equals(paired.id())) throw unavailable();
        persons.revoke(scope.id(), actor, reason, at);
        if (assignment.getRevokedAt() == null) {
            var before = reader.decode(reader.encode(assignment), RbacModels.Assignment.class);
            String next = id(), revoke = id();
            one(rbac.revoke(assignment.getId(), assignment.getVersion(), next, revoke, at));
            assignment.setVersion(next); assignment.setRevokedAt(at); assignment.setRevokeChangeId(revoke);
            rbacAudit(actor, assignment, before, change, at);
        }
        managementAudit.unifiedScope(scope, reader.decode(scope.selection(), Selection.class), reader.list(scope.memberBindings(), Binding.class), actor, change, "REVOKE", at);
    }

    private List<PersonBusinessScopes.Scope> affected(Intent intent) {
        if ("GRANT".equals(intent.kind())) return List.of();
        if ("DELEGATION_AUTHORITY".equals(intent.sourceType())) return persons.forAuthority(intent.sourceId());
        var s = "REPLACE".equals(intent.kind()) || "SCOPE".equals(intent.sourceType()) || "AUTHORIZATION".equals(intent.sourceType()) ? persons.scope(intent.sourceId()) : persons.assignmentScope(intent.sourceId());
        return s == null ? List.of() : List.of(s);
    }

    private List<Impact> impacts(Intent intent, LocalDateTime at) {
        var affected = affected(intent);
        var ids = affected.stream().map(PersonBusinessScopes.Scope::id).collect(java.util.stream.Collectors.toSet());
        var result = new ArrayList<Impact>();
        for (long user : affected.stream().mapToLong(PersonBusinessScopes.Scope::granteeUserId).distinct().toArray()) {
            for (var s : persons.forUser(user))
                if ("EFFECTIVE".equals(reader.reason(s, at))) {
                    var selection = reader.decode(s.selection(), Selection.class);
                    result.add(new Impact(Long.toString(user), s.dutyCode(), selection.target(), ids.contains(s.id()) ? "BECOMES_INEFFECTIVE" : "REMAINS_EFFECTIVE",
                            ids.contains(s.id()) && !"DELEGATION_AUTHORITY".equals(intent.sourceType()), source(s)));
                }
        }
        result.sort(Comparator.comparing(Impact::userId).thenComparing(Impact::dutyCode).thenComparing(i -> i.source().scopeGrantIds().get(0)));
        return result;
    }

    private String snapshot(RbacModels.Decision actor, Intent intent, LocalDateTime at) {
        var state = new ArrayList<Object>();
        state.add(actor.authorizationVersion());
        state.add(actor.catalogVersion());
        state.add(persons.epoch());
        state.add(intent);
        state.add(mapper.authorities());
        for (long user : rbac.rootUsers()) state.add(List.of(rbac.subject(user), rbac.assignments(user)));
        if ("GRANT".equals(intent.kind()) || "REPLACE".equals(intent.kind())) {
            long user = user(intent.granteeUserId());
            state.add(rbac.subject(user));
            state.add(persons.forUser(user));
        } else {
            state.add(impacts(intent, at));
            state.add(rbac.subject(sourceUser(intent)));
        }
        return hash(state);
    }

    private long sourceUser(Intent intent) {
        if ("DELEGATION_AUTHORITY".equals(intent.sourceType()))
            return mapper.authority(intent.sourceId()).getGranteeUserId();
        if ("REPLACE".equals(intent.kind()) || "SCOPE".equals(intent.sourceType()) || "AUTHORIZATION".equals(intent.sourceType())) return persons.scope(intent.sourceId()).granteeUserId();
        return rbac.assignment(intent.sourceId()).getUserId();
    }

    private void fresh(PreviewRow row, RbacModels.Decision actor, Intent intent, LocalDateTime at) {
        if (!row.getExpiresAt().isAfter(at) || !row.getSnapshotHash().equals(snapshot(actor, intent, at)))
            throw stale();
    }

    private PreviewRow previewRow(long actor, String id) {
        var row = mapper.preview(id);
        if (row == null || row.getActor() != actor) throw missing();
        return row;
    }

    private Preview preview(PreviewRow row, RbacModels.Decision d, Page<Impact> p) {
        return new Preview(row.getId(), d.authorizationVersion(), RbacTime.wire(row.getCreatedAt()), RbacTime.wire(row.getExpiresAt()), true, "ATOMIC", p.items(), p.next());
    }

    private EffectiveDuty effective(PersonBusinessScopes.Scope s, LocalDateTime at) {
        var selection = reader.decode(s.selection(), Selection.class);
        String reason = reader.reason(s, at);
        return new EffectiveDuty(s.dutyCode(), selection.target(), "EFFECTIVE".equals(reason), reason, source(s), selection.expiresAt(), selection.audienceTargets());
    }

    private Source source(PersonBusinessScopes.Scope s) {
        var selection = reader.decode(s.selection(), Selection.class);
        var member = reader.list(s.memberBindings(), Binding.class).stream()
                .filter(b -> b.userId() == s.granteeUserId() && Objects.equals(b.organizationId(), selection.target().organizationId())).findFirst().orElse(null);
        return new Source(List.of(s.assignmentId()), List.of(s.id()), s.authorityId(), member == null ? null : member.membershipId(), Long.toString(s.grantedBy()), RbacTime.wire(s.grantedAt()), s.changeId(), member == null ? null : member.personId(), member == null ? null : member.bindingId());
    }

    private Authority authority(AuthorityRow a, LocalDateTime at) {
        return authorityWithoutProof(a, reader.authorityStatus(a, at));
    }

    private Authority authorityWithoutProof(AuthorityRow a, String status) {
        var holder = reader.list(a.getMemberBindings(), Binding.class).stream().filter(b -> b.userId() == a.getGranteeUserId()).toList();
        return new Authority(a.getId(), Long.toString(a.getGranteeUserId()), Long.toString(a.getGrantedBy()), a.getRootAssignmentId(), reader.list(a.getCeilings(), Selection.class), a.getRecipientRule(),
                holder.size() == 1 ? holder.get(0).membershipId() : null, false, status, RbacTime.wire(a.getExpiresAt()), Long.toString(a.getRevision()), RbacTime.wire(a.getGrantedAt()), a.getReason(),
                RbacTime.wire(a.getRevokedAt()), a.getRevokedBy() == null ? null : Long.toString(a.getRevokedBy()), a.getRevocationReason(), holder.size() == 1 ? holder.get(0).personId() : null, holder.size() == 1 ? holder.get(0).bindingId() : null);
    }

    private <T> Page<T> page(long actor, String resource, List<T> items, int size, String cursor, LocalDateTime at, String version) {
        if (size < 1 || size > 100) throw RbacProblems.invalidQuery("pageSize");
        String binding = hash(List.of(resource, version, items));
        long offset = 0;
        if (cursor != null) {
            var c = mapper.cursor(cursor);
            if (c == null || c.actor() != actor || !binding.equals(c.binding()) || !c.expiresAt().isAfter(at))
                throw RbacProblems.invalidQuery("cursor");
            offset = c.itemOffset();
        }
        if (offset > items.size()) throw RbacProblems.invalidQuery("cursor");
        int end = (int) Math.min(items.size(), offset + size);
        String next = null;
        if (end < items.size()) {
            next = id();
            one(mapper.insertCursor(new CursorRow(next, actor, binding, end, at.plusMinutes(5))));
        }
        return new Page<>(List.copyOf(items.subList((int) offset, end)), next);
    }

    private static void beforeCommit(Runnable check) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                check.run();
            }
        });
    }

    private void bind(List<Binding> bindings, long user, String organization, LocalDateTime now) {
        var proof = persons.memberBinding(user, organization, now);
        if (proof == null) throw forbidden();
        bindings.add(new Binding(user, proof.personId(), proof.bindingId(), organization, proof.membershipId()));
    }

    private void revise(long user, LocalDateTime at) {
        var s = target(user);
        one(rbac.revise(user, s.getVersion(), id(), at));
    }

    private RbacModels.Subject target(long user) {
        var s = rbac.subject(user);
        if (s == null || s.getDeletedAt() != null) throw missing();
        if (!"ASSIGNMENT".equals(s.getSource())) throw forbidden();
        return s;
    }

    private static RbacModels.Grant root(RbacModels.Decision d) {
        return d.grants().stream().filter(g -> d.owns(g.assignmentId())).findFirst().orElse(null);
    }

    private static RbacModels.Grant requireRoot(RbacModels.Decision d) {
        var root = root(d);
        if (root == null) throw forbidden();
        return root;
    }

    private <T> T replay(long actor, String op, String target, String key, Object request, Class<T> type) {
        var c = mapper.command(actor, op, target, key);
        if (c == null) return null;
        if (!hash(request).equals(c.getFingerprint()))
            throw problem(HttpStatus.CONFLICT, "unified-idempotency-conflict");
        return reader.decode(c.getResultJson(), type);
    }

    private void saveCommand(String id, long actor, String op, String target, String key, Object request, Object result, Object audit, LocalDateTime now) {
        var c = new CommandRow();
        c.setId(id);
        c.setActor(actor);
        c.setOperation(op);
        c.setTarget(target);
        c.setCommandKey(key);
        c.setFingerprint(hash(request));
        c.setRequestJson(reader.encode(request));
        c.setResultJson(reader.encode(result));
        c.setAuditJson(reader.encode(audit));
        c.setOccurredAt(now);
        one(mapper.insertCommand(c));
        if (!"previewDutyChange".equals(op)) managementAudit.operation(id, actor, op, "AUTHORIZATION", target, now);
    }

    private void rbacAudit(long actor, RbacModels.Assignment a, RbacModels.Assignment before, String unified, LocalDateTime at) {
        String change = before == null ? a.getGrantChangeId() : a.getRevokeChangeId();
        var result = new RbacModels.Receipt(change, id(), before == null ? "GRANT" : "REVOKE", at, target(a.getUserId()).getVersion(), a);
        var c = new RbacModels.Change();
        c.setId(change);
        c.setOperationId(result.operationId());
        c.setActorUserId(actor);
        c.setTargetUserId(a.getUserId());
        c.setAssignmentId(a.getId());
        c.setAction(result.action());
        c.setIdempotencyKey(id().getBytes(StandardCharsets.US_ASCII));
        c.setRequestFingerprint(hash(List.of(unified, a.getId(), result.action())));
        c.setReason("Unified duty change " + unified);
        c.setBeforeSnapshot(before == null ? null : reader.encode(before));
        c.setAfterSnapshot(reader.encode(a));
        c.setReceipt(reader.encode(result));
        c.setOccurredAt(at);
        one(rbac.insertChange(c));
        managementAudit.duty(change, unified, actor, "ROLE_ASSIGNMENT", before == null ? "GRANT" : "REVOKE", Long.toString(a.getUserId()), null, "ROLE_ASSIGNMENT", a.getId(), managementAudit.tree(before), managementAudit.tree(a), at);
    }

    private Intent normalize(Intent i) {
        if (i == null) throw RbacProblems.invalid("intent");
        return new Intent(i.kind(), i.granteeUserId(), i.items() == null ? null : normalize(i.items(), true), i.authorityId(), "REPLACE".equals(i.kind()) ? null : i.sourceType(), i.sourceId(), reason(i.reason()));
    }

    private Intent resolveDeadlines(Intent intent, RbacModels.Decision decision, LocalDateTime at) {
        if (!("GRANT".equals(intent.kind()) || "REPLACE".equals(intent.kind())) || intent.items() == null
                || intent.items().stream().noneMatch(s -> s.expiresAt() == null)) return intent;
        boolean owner = root(decision) != null;
        List<Selection> ceilings = List.of();
        if (!owner) {
            var authority = mapper.authority(intent.authorityId());
            if (authority == null || authority.getGranteeUserId() != decision.userId()
                    || !"ACTIVE".equals(reader.authorityStatus(authority, at))) throw forbidden();
            ceilings = reader.list(authority.getCeilings(), Selection.class).stream()
                    .filter(s -> !expiry(s.expiresAt()).isAfter(authority.getExpiresAt()))
                    .toList();
        }
        var resolved = new ArrayList<Selection>();
        for (var item : intent.items()) {
            if (item.expiresAt() != null) {
                resolved.add(item);
                continue;
            }
            String deadline = owner ? NO_DUTY_DEADLINE : ceilings.stream()
                    .filter(c -> covers(c, withDeadline(item, c.expiresAt())))
                    .map(c -> expiry(c.expiresAt())).max(Comparator.naturalOrder())
                    .map(RbacTime::wire).orElseThrow(() -> forbidden());
            resolved.add(withDeadline(item, deadline));
        }
        return new Intent(intent.kind(), intent.granteeUserId(), normalize(resolved, false),
                intent.authorityId(), intent.sourceType(), intent.sourceId(), intent.reason());
    }

    private Selection withDeadline(Selection item, String deadline) {
        return new Selection(item.dutyCode(), item.target(), deadline, item.audienceTargets());
    }

    private AuthorityRequest normalize(AuthorityRequest r) {
        return new AuthorityRequest(r.granteeUserId(), normalize(r.ceilings(), false), r.recipientRule(), RbacTime.wire(expiry(r.expiresAt())), reason(r.reason()));
    }

    private List<Selection> normalize(List<Selection> values, boolean optionalDeadline) {
        if (values == null) throw RbacProblems.invalid("items");
        return values.stream().map(s -> new Selection(s.dutyCode(), s.target(), optionalDeadline && s.expiresAt() == null ? null : RbacTime.wire(expiry(s.expiresAt())),
                s.audienceTargets() == null ? null : s.audienceTargets().stream().sorted(Comparator.comparing(reader::encode)).toList())).sorted(Comparator.comparing(reader::encode)).toList();
    }

    private static String reason(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 2000)
            throw RbacProblems.invalid("reason");
        return value;
    }

    private static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) throw RbacProblems.invalid("Idempotency-Key");
    }

    private static String intentTarget(Intent i) {
        return "GRANT".equals(i.kind()) ? i.granteeUserId() : i.sourceType() + ":" + i.sourceId();
    }

    private String hash(Object value) {
        return RbacCatalog.hash(reader.encode(value).getBytes(StandardCharsets.UTF_8));
    }

    private static long user(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]*")) throw missing();
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw missing();
        }
    }

    private static LocalDateTime expiry(String value) {
        var t = RbacTime.expiry(value);
        if (t == null) throw RbacProblems.invalid("expiresAt");
        return t;
    }

    private static void future(LocalDateTime expiry, LocalDateTime at) {
        if (!expiry.isAfter(at)) throw RbacProblems.invalid("expiresAt");
    }

    private static void self(long actor, long target) {
        if (actor == target) throw forbidden();
    }

    private static void one(int count) {
        if (count != 1) throw unavailable();
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
