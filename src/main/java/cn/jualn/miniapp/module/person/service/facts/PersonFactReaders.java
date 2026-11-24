package cn.jualn.miniapp.module.person.service.facts;

import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;
import cn.jualn.miniapp.module.person.service.binding.PersonBindingTrust;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import cn.jualn.miniapp.module.person.bo.PersonBO.Action;
import cn.jualn.miniapp.module.person.bo.PersonBO.Domain;
import cn.jualn.miniapp.module.person.bo.PersonBO.LocalMembership;
import cn.jualn.miniapp.module.person.bo.PersonBO.Member;
import cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationGroup;
import cn.jualn.miniapp.module.person.bo.PersonBO.Summary;
import cn.jualn.miniapp.module.person.bo.PersonBO.Verification;
import cn.jualn.miniapp.module.person.entity.PersonRows;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static cn.jualn.miniapp.module.person.PersonProblems.denied;
import static cn.jualn.miniapp.module.person.PersonProblems.invalid;
import static cn.jualn.miniapp.module.person.PersonProblems.missing;
import static cn.jualn.miniapp.module.person.PersonProblems.problem;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;

/**
 * Public owner boundary. All results are bound to one live local MySQL transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PersonFactReaders {
    private final PersonMapper mapper;
    private final ManagedPersonMapper managed;
    private final TransactionalAuthorization authorization;
    private final PersonCatalog catalog;
    private UnifiedDutyReader businessDuties;

    @Autowired
    void businessDuties(ObjectProvider<UnifiedDutyReader> provider) {
        businessDuties = provider.getIfAvailable();
    }

    public boolean localOrganizationDuty(long actor, String organization, LocalDateTime at) {
        return localOrganizationDuty(actor, organization, false, at);
    }

    public boolean localOrganizationDuty(long actor, String organization, boolean write, LocalDateTime at) {
        return localOrganizationDuties(actor, write, at).contains(organization);
    }

    public List<String> localOrganizationDuties(long actor, LocalDateTime at) {
        return localOrganizationDuties(actor, false, at);
    }

    private List<String> localOrganizationDuties(long actor, boolean write, LocalDateTime at) {
        if (businessDuties == null) return List.of();
        return (write ? List.of("ORG_MEMBER_MAINTAIN") : List.of("ORG_MEMBER_MAINTAIN", "ORG_MEMBER_READ")).stream()
                .flatMap(code -> businessDuties.effective(actor, code, at).stream())
                .map(s -> businessDuties.decode(s.selection(), UnifiedModels.Selection.class).target().organizationId()).distinct().sorted().toList();
    }

    private List<PersonMapper.DutyTarget> identityDuties(long actor, LocalDateTime at) {
        if (businessDuties == null) return List.of();
        return List.of("PERSON_IDENTITY_MAINTAIN", "PERSON_IDENTITY_READ", "PERSON_ORGANIZATION_READ").stream()
                .flatMap(code -> businessDuties.effective(actor, code, at).stream().map(s -> {
                    var t = businessDuties.decode(s.selection(), UnifiedModels.Selection.class).target();
                    return new PersonMapper.DutyTarget("PERSON_ORGANIZATION_READ".equals(code) ? "ORGANIZATION" : "IDENTITY", t.type(),
                            "PERSON".equals(t.type()) ? t.personId() : t.organizationId(), "PERSON_IDENTITY_MAINTAIN".equals(code) ? "READ,MAINTAIN" : "READ", s.id());
                })).toList();
    }

    public enum Outcome {KNOWN, INDETERMINATE}

    public enum Decision {ALLOW, DENY, INDETERMINATE}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IdentityFact(PersonReadContext context, Outcome outcome, String reason, String presence,
                               String stageId, String status,
                               @JsonSerialize(using = UtcTimeSerializer.class) LocalDateTime validFrom,
                               @JsonSerialize(using = UtcTimeSerializer.class) LocalDateTime validUntil,
                               String version, String source) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MembershipFact(PersonReadContext context, Outcome outcome, String reason,
                                 List<String> organizationIds, String version, String source) {
        public MembershipFact {
            if (organizationIds != null) organizationIds = List.copyOf(organizationIds);
        }
    }

    public record ScopeDecision(PersonReadContext context, Decision decision, String reason) {
    }

    public record LocalMembershipFact(PersonReadContext context, Outcome outcome, String reason, LocalMembership fact) {
    }

    /**
     * Positive/absent evidence for one organization only. Never proves the complete person set or roster.
     */
    public LocalMembershipFact localMembership(String person, String organization, PersonReadContext context) {
        return localMembershipAt(person, organization, context, context.evaluatedAt());
    }

    public LocalMembershipFact localMembershipFinal(String person, String organization, PersonReadContext context) {
        return localMembershipAt(person, organization, context, context.finalEvaluatedAt());
    }

    private LocalMembershipFact localMembershipAt(String person, String organization, PersonReadContext context, LocalDateTime at) {
        context.requirePerson(person);
        try {
            if (mapper.corruptPerson(person) != 0)
                return new LocalMembershipFact(context, Outcome.INDETERMINATE, "SOURCE_CORRUPT", null);
            var org = mapper.organization(organization);
            if (org == null || !"ACTIVE".equals(org.getStatus()))
                return new LocalMembershipFact(context, Outcome.INDETERMINATE, "ORGANIZATION_UNAVAILABLE", null);
            var fact = PersonProjection.localMembership(person, organization, mapper.head(person), mapper.localMembership(person, organization), mapper.memberships(person), at);
            return fact.verification() == Verification.VERIFIED ? new LocalMembershipFact(context, Outcome.KNOWN, null, fact)
                    : new LocalMembershipFact(context, Outcome.INDETERMINATE, fact.verification().name(), null);
        } catch (DataAccessException failure) {
            return new LocalMembershipFact(context, Outcome.INDETERMINATE, "SOURCE_UNAVAILABLE", null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueryResult(PersonReadContext context, String decision, String reason, String planHandle) {
    }

    public static class UtcTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeString(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value.atOffset(ZoneOffset.UTC)));
        }
    }

    /**
     * Must be the first read in a new RR transaction. Gate and owner locks precede MVCC snapshot.
     */
    public PersonReadContext open(long... users) {
        return open(PersonAuthorityGate.Access.SHARED, users);
    }

    public PersonReadContext open(PersonAuthorityGate.Access access, long... users) {
        if (!Integer.valueOf(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ)
                .equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) throw unavailable();
        try {
            long epoch = authorization.begin(access, users);
            var context = new PersonReadContext(epoch, mapper.now(), users);
            if (mapper.corruptSource() != 0 || mapper.corruptFacts() != 0) throw unavailable();
            return context;
        } catch (DataAccessException failure) {
            throw unavailable();
        }
    }

    public IdentityFact identity(String person, PersonReadContext context) {
        return identityAt(person, context, context.evaluatedAt());
    }

    private IdentityFact identityAt(String person, PersonReadContext context, LocalDateTime evaluatedAt) {
        context.requirePerson(person);
        try {
            if (mapper.corruptPerson(person) != 0) return unknownIdentity(context, "SOURCE_CORRUPT");
            var h = mapper.head(person);
            if (h == null || "UNKNOWN".equals(h.getIdentityVerification()))
                return unknownIdentity(context, "UNVERIFIED");
            if ("DISPUTED".equals(h.getIdentityVerification())) return unknownIdentity(context, "DISPUTED");
            var fact = PersonProjection.identity(person, h);
            if (fact.presence() == null || fact.verifiedByUserId() == null || fact.verifiedAt() == null)
                return unknownIdentity(context, "SOURCE_CORRUPT");
            if ("NONE".equals(fact.presence()))
                return new IdentityFact(context, Outcome.KNOWN, null, "NONE", null, null, null, null, fact.version(), "LOCAL_MANUAL");
            var r = fact.currentRecord();
            if (r == null || r.validFrom() == null) return unknownIdentity(context, "SOURCE_CORRUPT");
            if (!catalog.confirmed(r.stageId())) return unknownIdentity(context, "STAGE_UNCONFIRMED");
            String status = "ACTIVE".equals(r.status()) && r.validUntil() != null && !r.validUntil().isAfter(evaluatedAt) ? "EXPIRED" : r.status();
            var event = mapper.event(h.getIdentityChangeId());
            if (event == null || !Set.of("LOCAL_MANUAL", "ROSTER_IMPORT", "ROSTER_EXACT_MATCH", "ADMIN_VERIFIED_SOURCE").contains(event.getSource()))
                return unknownIdentity(context, "SOURCE_CORRUPT");
            return new IdentityFact(context, Outcome.KNOWN, null, "RECORDED", r.stageId(), status, r.validFrom(), r.validUntil(), fact.version(), event.getSource());
        } catch (DataAccessException failure) {
            return unknownIdentity(context, "SOURCE_UNAVAILABLE");
        }
    }

    private IdentityFact unknownIdentity(PersonReadContext c, String reason) {
        return new IdentityFact(c, Outcome.INDETERMINATE, reason, null, null, null, null, null, null, null);
    }

    public MembershipFact memberships(String person, PersonReadContext context) {
        return membershipsAt(person, context, context.evaluatedAt());
    }

    public MembershipFact membershipsFinal(String person, PersonReadContext context) {
        return membershipsAt(person, context, context.finalEvaluatedAt());
    }

    private MembershipFact membershipsAt(String person, PersonReadContext context, LocalDateTime at) {
        context.requirePerson(person);
        try {
            if (mapper.corruptPerson(person) != 0) return unknownMembership(context, "SOURCE_CORRUPT");
            var h = mapper.head(person);
            if (h == null || "UNKNOWN".equals(h.getMembershipVerification()))
                return unknownMembership(context, "UNVERIFIED");
            if ("DISPUTED".equals(h.getMembershipVerification())) return unknownMembership(context, "DISPUTED");
            if (h.getMembershipVerifiedAt() == null || h.getMembershipVerifiedBy() == null)
                return unknownMembership(context, "SOURCE_CORRUPT");
            var rows = mapper.memberships(person);
            for (var row : rows) {
                var org = mapper.organization(row.getOrganizationId());
                if (org == null) return unknownMembership(context, "SOURCE_CORRUPT");
                if (!"ACTIVE".equals(org.getStatus())) return unknownMembership(context, "ORGANIZATION_INACTIVE");
            }
            var current = PersonProjection.memberships(person, h, rows, at);
            return new MembershipFact(context, Outcome.KNOWN, null, current.currentMemberships().stream().map(Member::organizationId).toList(), current.version(), "LOCAL_MANUAL");
        } catch (DataAccessException failure) {
            return unknownMembership(context, "SOURCE_UNAVAILABLE");
        }
    }

    private MembershipFact unknownMembership(PersonReadContext c, String reason) {
        return new MembershipFact(c, Outcome.INDETERMINATE, reason, null, null, null);
    }

    public ScopeDecision authorizePerson(long actor, String person, Domain domain, Action action, PersonReadContext context) {
        context.require();
        return authorizeAt(actor, person, domain, action, context, context.evaluatedAt());
    }

    /**
     * Final current-time checks retain the same locked source version and transaction.
     */
    public LocalDateTime currentTime(PersonReadContext context) {
        context.require();
        return mapper.now();
    }

    public IdentityFact identityNow(String person, PersonReadContext context) {
        return identityAt(person, context, currentTime(context));
    }

    public LocalDateTime beginFinalCheck(PersonReadContext context) {
        context.beginFinalCheck(currentTime(context));
        return context.finalEvaluatedAt();
    }

    public IdentityFact identityFinal(String person, PersonReadContext context) {
        return identityAt(person, context, context.finalEvaluatedAt());
    }

    public ScopeDecision authorizeFinal(long actor, String person, Domain domain, Action action, PersonReadContext context) {
        return authorizeAt(actor, person, domain, action, context, context.finalEvaluatedAt());
    }

    public ScopeDecision authorizeNow(long actor, String person, Domain domain, Action action, PersonReadContext context) {
        return authorizeAt(actor, person, domain, action, context, currentTime(context));
    }

    ScopeDecision authorizeAt(long actor, String person, Domain domain, Action action, PersonReadContext context, LocalDateTime now) {
        context.requireUser(actor);
        context.requirePerson(person);
        if (!supported(domain, action)) return new ScopeDecision(context, Decision.DENY, "ACTION_UNSUPPORTED");
        if (action == Action.MAINTAIN && managed.everBound(person, actor))
            return new ScopeDecision(context, Decision.DENY, "SELF_GOVERNANCE");
        try {
            if (mapper.corruptScopes(actor) != 0 || mapper.corruptPerson(person) != 0)
                return new ScopeDecision(context, Decision.INDETERMINATE, "SOURCE_CORRUPT");
            if (!authorization.current(actor, now).accountEligible())
                return new ScopeDecision(context, Decision.DENY, "ACCOUNT_BLOCKED");
            var q = query(actor, List.of(new PersonMapper.Check(domain.name(), action.name())), now, person, null, 1, 0);
            if (mapper.indeterminateCandidates(q) != 0)
                return new ScopeDecision(context, Decision.INDETERMINATE, "MEMBERSHIP_UNKNOWN");
            return mapper.allowed(q) == 1 ? new ScopeDecision(context, Decision.ALLOW, "OWNING_POLICY") : new ScopeDecision(context, Decision.DENY, "NO_EFFECTIVE_SCOPE");
        } catch (DataAccessException failure) {
            return new ScopeDecision(context, Decision.INDETERMINATE, "SOURCE_UNAVAILABLE");
        }
    }

    public QueryResult plan(long actor, Domain domain, Action action, String organizationId, PersonReadContext context) {
        context.requireUser(actor);
        if (!supported(domain, action)) return new QueryResult(context, "DENIED", "ACTION_UNSUPPORTED", null);
        try {
            if (mapper.corruptScopes(actor) != 0)
                return new QueryResult(context, "INDETERMINATE", "SOURCE_CORRUPT", null);
            if (!authorization.current(actor, context.evaluatedAt()).accountEligible())
                return new QueryResult(context, "DENIED", "ACCOUNT_BLOCKED", null);
            if (!hasOperate(actor, domain, action, context.evaluatedAt()))
                return new QueryResult(context, "DENIED", "NO_EFFECTIVE_SCOPE", null);
            var q = query(actor, List.of(new PersonMapper.Check(domain.name(), action.name())), context.evaluatedAt(), null, organizationId, 1, 0);
            if (mapper.indeterminateCandidates(q) != 0)
                return new QueryResult(context, "INDETERMINATE", "MEMBERSHIP_UNKNOWN", null);
            return new QueryResult(context, "READY", null, context.plan(new PersonReadContext.Plan(actor, domain, action, organizationId)));
        } catch (DataAccessException failure) {
            return new QueryResult(context, "INDETERMINATE", "SOURCE_UNAVAILABLE", null);
        }
    }

    public record ControlledPage(List<String> personIds, int page, int pageSize, long totalItems) {
        public ControlledPage {
            personIds = List.copyOf(personIds);
        }
    }

    /**
     * Trusted executable parameters for Points' read-only projections of its own participants.
     * The owner predicate is included by Points SQL; no client can construct this plan.
     */
    public static final class PointsQuery {
        public record Check(String domain, String action) {
        }

        private final PersonReadContext context;
        private final long actor;
        private final String personId;
        private final String organizationId;

        private PointsQuery(PersonReadContext context, long actor, String personId, String organizationId) {
            this.context = context;
            this.actor = actor;
            this.personId = personId;
            this.organizationId = organizationId;
        }

        public boolean rootOwner() {
            context.require();
            return false;
        }

        public long actor() {
            context.requireUser(actor);
            return actor;
        }

        public List<Check> checks() {
            context.require();
            return List.of(new Check("POINTS", "READ"));
        }

        public List<PersonMapper.DutyTarget> dutyTargets() {
            context.require();
            return List.of();
        }

        public LocalDateTime now() {
            context.require();
            return context.evaluatedAt();
        }

        public String personId() {
            context.require();
            return personId;
        }

        public String organizationId() {
            context.require();
            return organizationId;
        }
    }

    public record PointsPlanResult(String decision, String reason, PointsQuery plan) {
    }

    public PointsPlanResult pointsPlan(long actor, String personId, String organizationId, PersonReadContext context) {
        context.requireUser(actor);
        try {
            if (mapper.corruptScopes(actor) != 0) return new PointsPlanResult("INDETERMINATE", "SOURCE_CORRUPT", null);
            if (!authorization.current(actor, context.evaluatedAt()).accountEligible() || !hasOperate(actor, Domain.POINTS, Action.READ, context.evaluatedAt()))
                return new PointsPlanResult("DENIED", "NO_EFFECTIVE_SCOPE", null);
        } catch (DataAccessException failure) {
            return new PointsPlanResult("INDETERMINATE", "SOURCE_UNAVAILABLE", null);
        }
        var query = query(actor, List.of(new PersonMapper.Check("POINTS", "READ")), context.evaluatedAt(), personId, organizationId, 1, 0);
        try {
            if (mapper.indeterminatePointsCandidates(query) != 0)
                return new PointsPlanResult("INDETERMINATE", "MEMBERSHIP_UNKNOWN", null);
            if (organizationId != null && mapper.pointsOrganizationVisible(query) == 0) throw missing();
        } catch (DataAccessException failure) {
            return new PointsPlanResult("INDETERMINATE", "MEMBERSHIP_SOURCE_UNAVAILABLE", null);
        }
        return new PointsPlanResult("READY", null, new PointsQuery(context, actor, personId, organizationId));
    }

    public PointsQuery pointsQuery(long actor, String personId, String organizationId, PersonReadContext context) {
        var result = pointsPlan(actor, personId, organizationId, context);
        if ("DENIED".equals(result.decision())) throw denied();
        if (!"READY".equals(result.decision())) throw unavailable();
        return result.plan();
    }

    public ControlledPage query(String planHandle, int page, int size, PersonReadContext context) {
        var plan = context.plan(planHandle);
        var q = query(plan.actor(), List.of(new PersonMapper.Check(plan.domain().name(), plan.action().name())), context.evaluatedAt(), null, plan.organizationId(), size, (long) (page - 1) * size);
        if (page < 1 || size < 1 || size > 100) throw invalid("page");
        return new ControlledPage(mapper.personPage(q).stream().map(Summary::personId).toList(), page, size, mapper.personCount(q));
    }

    public long count(String planHandle, PersonReadContext context) {
        var p = context.plan(planHandle);
        return mapper.personCount(query(p.actor(), List.of(new PersonMapper.Check(p.domain().name(), p.action().name())), context.evaluatedAt(), null, p.organizationId(), 1, 0));
    }

    /**
     * Current authorized User candidates only. Not a Points participant/ledger aggregate.
     */
    public List<OrganizationGroup> group(String planHandle, PersonReadContext context) {
        var p = context.plan(planHandle);
        // A group requires known membership even when direct maintenance scope permitted UNKNOWN.
        var integrity = query(p.actor(), List.of(new PersonMapper.Check(p.domain().name(), p.action().name())), context.evaluatedAt(), null, "GROUP_REQUIRES_KNOWN", 1, 0);
        if (mapper.indeterminateCandidates(integrity) != 0) throw unavailable();
        return mapper.personGroups(query(p.actor(), integrity.checks(), context.evaluatedAt(), null, p.organizationId(), 1, 0));
    }

    public boolean rootOwner(long actor, LocalDateTime now) {
        var decision = authorization.current(actor, now);
        return decision.grants().stream().anyMatch(g -> decision.owns(g.assignmentId()));
    }

    private boolean rootOperation(long actor, String domain, String action, LocalDateTime at) {
        if (!Set.of("IDENTITY", "ORGANIZATION").contains(domain)) return false;
        String namespace = "IDENTITY".equals(domain) ? "IDENTITY" : "ORGANIZATION";
        String read = "IDENTITY".equals(domain) ? "identity:read" : "organization:read";
        String write = "IDENTITY".equals(domain) ? "identity:map-manage" : "organization:map-manage";
        return authorization.current(actor, at).managementOwner(namespace, "READ".equals(action) ? new String[]{read} : new String[]{read, write}) != null;
    }

    public PersonMapper.Query query(long actor, List<PersonMapper.Check> checks, LocalDateTime now, String person, String organization, int limit, long offset) {
        return new PersonMapper.Query(actor, checks, now, person, organization, limit, offset, !checks.isEmpty() && checks.stream().allMatch(c -> rootOperation(actor, c.domain(), c.action(), now)), identityDuties(actor, now));
    }

    public boolean hasOperate(long actor, Domain domain, Action action, LocalDateTime now) {
        if (mapper.corruptScopes(actor) != 0) throw unavailable();
        if (rootOperation(actor, domain.name(), action.name(), now)) return true;
        if (identityDuties(actor, now).stream().anyMatch(t -> domain.name().equals(t.domain()) && java.util.Arrays.asList(t.actions().split(",")).contains(action.name())))
            return true;
        return mapper.actorScopes(actor).stream().anyMatch(s -> "OPERATE".equals(s.getMode()) && domain.name().equals(s.getDomain()) && actions(s).contains(action)
                && live(s, now) && ("ROOT_OWNER".equals(s.getSource()) || live(mapper.scope(s.getAuthorityGrantId()), now)));
    }

    /**
     * Exact source target for a roster row without a User. It grants no whole-person access.
     */
    public boolean rosterOrganizationTarget(long actor, String organization, Domain domain, Action action, LocalDateTime now) {
        if (mapper.corruptScopes(actor) != 0) throw unavailable();
        if (rootOperation(actor, domain.name(), action.name(), now)) return true;
        if (domain == Domain.ORGANIZATION && localOrganizationDuty(actor, organization, action == Action.MAINTAIN, now))
            return true;
        if (identityDuties(actor, now).stream().anyMatch(t -> domain.name().equals(t.domain()) && "ORGANIZATION".equals(t.targetType())
                && organization.equals(t.targetId()) && java.util.Arrays.asList(t.actions().split(",")).contains(action.name())))
            return true;
        return mapper.actorScopes(actor).stream().anyMatch(s -> "OPERATE".equals(s.getMode()) && domain.name().equals(s.getDomain())
                && "ORGANIZATION".equals(s.getTargetType()) && organization.equals(s.getTargetId()) && actions(s).contains(action) && live(s, now)
                && ("ROOT_OWNER".equals(s.getSource()) || live(mapper.scope(s.getAuthorityGrantId()), now)));
    }

    /**
     * Resolve an account only through its authoritative current binding; never reuse its ID.
     */
    public String currentPerson(long user) {
        var binding = managed.accountBinding(user);
        if (binding == null) throw problem(HttpStatus.CONFLICT, "person-binding-conflict");
        PersonBindingTrust.require(binding);
        var profile = managed.profile(binding.getPersonId());
        if (profile == null) throw unavailable();
        if (!"ACTIVE".equals(profile.getStatus()))
            throw problem(HttpStatus.CONFLICT, "person-archived");
        return binding.getPersonId();
    }

    public IdentityFact identity(long user, PersonReadContext c) {
        c.requireUser(user);
        return identity(currentPerson(user), c);
    }

    public IdentityFact identityNow(long user, PersonReadContext c) {
        c.requireUser(user);
        return identityNow(currentPerson(user), c);
    }

    public IdentityFact identityFinal(long user, PersonReadContext c) {
        c.requireUser(user);
        return identityFinal(currentPerson(user), c);
    }

    public MembershipFact memberships(long user, PersonReadContext c) {
        c.requireUser(user);
        return memberships(currentPerson(user), c);
    }

    public MembershipFact membershipsFinal(long user, PersonReadContext c) {
        c.requireUser(user);
        return membershipsFinal(currentPerson(user), c);
    }

    public LocalMembershipFact localMembership(long user, String org, PersonReadContext c) {
        c.requireUser(user);
        return localMembership(currentPerson(user), org, c);
    }

    public LocalMembershipFact localMembershipFinal(long user, String org, PersonReadContext c) {
        c.requireUser(user);
        return localMembershipFinal(currentPerson(user), org, c);
    }

    public ScopeDecision authorizePerson(long actor, long user, Domain d, Action a, PersonReadContext c) {
        c.requireUser(user);
        return authorizePerson(actor, currentPerson(user), d, a, c);
    }

    public ScopeDecision authorizeNow(long actor, long user, Domain d, Action a, PersonReadContext c) {
        c.requireUser(user);
        return authorizeNow(actor, currentPerson(user), d, a, c);
    }

    public ScopeDecision authorizeFinal(long actor, long user, Domain d, Action a, PersonReadContext c) {
        c.requireUser(user);
        return authorizeFinal(actor, currentPerson(user), d, a, c);
    }

    public static boolean live(PersonRows.Scope s, LocalDateTime now) {
        return s != null && s.getRevokedAt() == null && !s.getValidFrom().isAfter(now) && (s.getValidUntil() == null || s.getValidUntil().isAfter(now));
    }

    public static Set<Action> actions(PersonRows.Scope s) {
        return Set.copyOf(java.util.Arrays.stream(s.getActions().split(",")).map(Action::valueOf).toList());
    }

    public static boolean supported(Domain domain, Action action) {
        return domain == Domain.POINTS ? Set.of(Action.READ, Action.REVIEW, Action.REVERSE).contains(action) : Set.of(Action.READ, Action.MAINTAIN).contains(action);
    }
}
