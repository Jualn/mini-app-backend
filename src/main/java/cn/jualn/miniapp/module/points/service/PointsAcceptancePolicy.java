package cn.jualn.miniapp.module.points.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.authorization.service.UnifiedDutyReader;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.points.bo.PointsBO.Action;
import cn.jualn.miniapp.module.points.bo.PointsBO.Status;
import cn.jualn.miniapp.module.points.bo.PointsBO.Application;
import cn.jualn.miniapp.module.points.bo.PointsBO.AcceptingOrganization;
import cn.jualn.miniapp.module.points.bo.PointsBO.AcceptingOrganizations;
import cn.jualn.miniapp.module.points.bo.PointsBO.Acceptance;
import cn.jualn.miniapp.module.points.bo.PointsBO.Query;
import cn.jualn.miniapp.module.points.mapper.PointsMapper;
import cn.jualn.miniapp.module.points.mapper.PointsR3Mapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import cn.jualn.miniapp.module.points.PointsProblems.Dependency;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;

import static cn.jualn.miniapp.module.points.PointsProblems.unavailable;
import static cn.jualn.miniapp.module.points.PointsProblems.conflict;
import static cn.jualn.miniapp.module.points.PointsProblems.forbidden;
import static cn.jualn.miniapp.module.points.PointsProblems.missing;
import static cn.jualn.miniapp.module.points.PointsProblems.invalid;

/**
 * Application target, complete person ledger, and statistics are separate duties. No legacy scope conversion.
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PointsAcceptancePolicy {
    private final PointsMapper db;
    private final TransactionalAuthorization authorization;
    private final PersonFactReaders facts;
    private final PersonBusinessScopes persons;
    private final UnifiedDutyReader duties;
    private final PointsR3Mapper queries;

    /**
     * One scope row is one approval, even when another row has the same authority/grantor.
     */
    public record SourceTarget(String scopeGrantId, String assignmentId, UnifiedModels.Target target) {
    }

    public static final class Plan {
        private final PersonReadContext context;
        private final List<SourceTarget> sources;
        private final List<String> organizations;
        private List<SourceTarget> correctionSources = List.of();

        public List<UnifiedModels.Target> correctionTargets() {
            context.snapshot();
            return correctionSources.stream().map(SourceTarget::target).toList();
        }

        private final String person;
        private final String organization;

        private Plan(PersonReadContext context, List<SourceTarget> sources, List<String> organizations, String person, String organization) {
            this.context = context;
            this.sources = List.copyOf(sources);
            this.organizations = List.copyOf(organizations);
            this.person = person;
            this.organization = organization;
        }

        public List<SourceTarget> sources() {
            context.snapshot();
            return sources;
        }

        public List<UnifiedModels.Target> targets() {
            context.snapshot();
            return sources.stream().map(SourceTarget::target).toList();
        }

        public List<String> organizations() {
            context.snapshot();
            return organizations;
        }

        public LocalDateTime now() {
            context.snapshot();
            return context.evaluatedAt();
        }

        public String person() {
            context.snapshot();
            return person;
        }

        public String organization() {
            context.snapshot();
            return organization;
        }

        public List<UnifiedModels.Target> getTargets() {
            return targets();
        }

        public List<String> getOrganizations() {
            return organizations();
        }

        public LocalDateTime getNow() {
            return now();
        }

        public String getPerson() {
            return person();
        }

        public String getOrganization() {
            return organization();
        }
    }

    public Plan applications(long actor, String person, String organization, Query query, PersonReadContext context) {
        var sources = sources(actor, "POINTS_REVIEW", context.evaluatedAt());
        var targets = sources.stream().map(SourceTarget::target).toList();
        var corrections = sources(actor, "POINTS_CORRECTION", context.evaluatedAt());
        if (targets.isEmpty() && corrections.isEmpty()) throw missing();
        var plan = new Plan(context, sources, List.of(), person, organization);
        plan.correctionSources = List.copyOf(corrections);
        if (!targets.isEmpty() && queries.unresolvedApplications(plan, query) != 0)
            throw unavailable(Dependency.PERSON_RESOLVER);
        for (var pending : queries.pendingOrganizations(plan, query)) qualified(pending);
        return plan;
    }

    public Plan people(long actor, String person, String organization, PersonReadContext context) {
        var sources = sources(actor, "POINTS_PERSON_LEDGER", context.evaluatedAt());
        var targets = sources.stream().map(SourceTarget::target).toList();
        if (targets.isEmpty()) throw missing();
        var plan = new Plan(context, sources, List.of(), person, organization);
        if (queries.unknownParticipants(plan) != 0) throw unavailable(Dependency.PERSON_RESOLVER);
        if (organization != null && (!persons.allOrganizations().contains(organization)
                || targets.stream().noneMatch(t -> "PLATFORM".equals(t.type()) || organization.equals(t.organizationId())) && queries.participantCount(plan) == 0))
            throw missing();
        return plan;
    }

    public Plan statistics(long actor, String organization, boolean accepting, PersonReadContext context) {
        var sources = sources(actor, accepting ? "POINTS_STATS_ACCEPTING" : "POINTS_STATS_CURRENT", context.evaluatedAt());
        var targets = sources.stream().map(SourceTarget::target).toList();
        if (targets.isEmpty()) throw missing();
        var directory = accepting ? persons.allOrganizations() : persons.organizations();
        var allowed = directory.stream().filter(id -> targets.stream().anyMatch(t -> "PLATFORM".equals(t.type()) || id.equals(t.organizationId())))
                .filter(id -> organization == null || organization.equals(id)).toList();
        if (organization != null && allowed.isEmpty()) throw missing();
        if (accepting) {
            if (!allowed.isEmpty() && queries.unresolvedLedger() != 0) throw unavailable(Dependency.PERSON_RESOLVER);
        } else for (var id : allowed)
            try {
                for (String member : persons.rosterMembers(id, context.evaluatedAt()))
                    persons.completeMemberships(member, context.evaluatedAt());
            } catch (ContractProblemException failure) {
                var problem = unavailable(Dependency.PERSON_RESOLVER);
                problem.initCause(failure);
                throw problem;
            }
        return new Plan(context, sources, allowed, null, organization);
    }

    public boolean qualified(String organization) {
        var q = db.qualification(organization);
        if (q == null || q.evidenceId() == null || !organization.equals(q.evidenceOrganizationId()) || !"VERIFIED".equals(q.verification()) || q.enabled() == null)
            throw unavailable(Dependency.BUSINESS_ELIGIBILITY);
        return q.enabled();
    }

    /**
     * An existential proof may be positive from one trusted local cycle; negative needs a complete set.
     */
    public boolean hasAcceptingOrganization(long user, PersonReadContext context, boolean last) {
        var membership = last ? facts.membershipsFinal(user, context) : facts.memberships(user, context);
        boolean complete = membership.outcome() == PersonFactReaders.Outcome.KNOWN;
        if (!complete && !Set.of("UNVERIFIED", "DISPUTED").contains(membership.reason()))
            throw unavailable(Dependency.PERSON_RESOLVER);
        var at = last ? context.finalEvaluatedAt() : context.evaluatedAt();
        var organizations = complete ? membership.organizationIds() : persons.positiveOrganizations(user, at);
        ContractProblemException unknown = complete ? null : unavailable(Dependency.PERSON_RESOLVER);
        for (var organization : organizations) {
            var local = last ? facts.localMembershipFinal(user, organization, context) : facts.localMembership(user, organization, context);
            if (local.outcome() != PersonFactReaders.Outcome.KNOWN) {
                unknown = unavailable(Dependency.PERSON_RESOLVER);
                continue;
            }
            try {
                if ("PRESENT".equals(local.fact().presence()) && qualified(organization)) return true;
            } catch (ContractProblemException failure) {
                if (failure.getStatus() != HttpStatus.SERVICE_UNAVAILABLE) throw failure;
                unknown = failure;
            }
        }
        if (unknown != null) throw unknown;
        return false;
    }

    public AcceptingOrganizations candidates(long user, PersonReadContext context) {
        return candidates(user, context, false);
    }

    public AcceptingOrganizations candidates(long user, PersonReadContext context, boolean last) {
        var membership = last ? facts.membershipsFinal(user, context) : facts.memberships(user, context);
        if (membership.outcome() != PersonFactReaders.Outcome.KNOWN) throw unavailable(Dependency.PERSON_RESOLVER);
        var result = new ArrayList<AcceptingOrganization>();
        for (String organization : membership.organizationIds())
            if (qualified(organization)) {
                var local = last ? facts.localMembershipFinal(user, organization, context) : facts.localMembership(user, organization, context);
                if (local.outcome() != PersonFactReaders.Outcome.KNOWN || !"PRESENT".equals(local.fact().presence()))
                    throw unavailable(Dependency.PERSON_RESOLVER);
                result.add(new AcceptingOrganization(organization, persons.organizationName(organization), local.fact().membershipId()));
            }
        result.sort(Comparator.comparing(AcceptingOrganization::organizationId));
        return new AcceptingOrganizations(last ? context.finalEvaluatedAt() : context.evaluatedAt(), List.copyOf(result));
    }

    public Acceptance bind(long user, String organization, PersonReadContext context) {
        if (organization == null || organization.isBlank()) throw invalid("acceptingOrganizationId");
        var member = facts.localMembership(user, organization, context);
        if (member.outcome() != PersonFactReaders.Outcome.KNOWN) throw unavailable(Dependency.PERSON_RESOLVER);
        if (!"PRESENT".equals(member.fact().presence()) || !qualified(organization)) throw forbidden("ineligible");
        return new Acceptance(organization, member.fact().membershipId());
    }

    public void currentBasis(Application app, PersonReadContext context, boolean last) {
        if (!validBasis(app, context, last)) throw conflict("acceptance-unavailable");
    }

    private boolean validBasis(Application app, PersonReadContext context, boolean last) {
        if (app.acceptingOrganizationId() == null || app.membershipId() == null) return false;
        var member = last ? facts.localMembershipFinal(app.personId(), app.acceptingOrganizationId(), context) : facts.localMembership(app.personId(), app.acceptingOrganizationId(), context);
        if (member.outcome() != PersonFactReaders.Outcome.KNOWN) throw unavailable(Dependency.PERSON_RESOLVER);
        return "PRESENT".equals(member.fact().presence()) && app.membershipId().equals(member.fact().membershipId()) && qualified(app.acceptingOrganizationId());
    }

    public void application(long actor, Application app, Action action, PersonReadContext context, boolean last) {
        var at = last ? context.finalEvaluatedAt() : context.evaluatedAt();
        if (app.acceptingOrganizationId() == null || app.membershipId() == null)
            throw unavailable(Dependency.PERSON_RESOLVER);
        if (action == Action.REVERSE || action == Action.CORRECT) {
            if (app.status() != Status.APPROVED || !correctionAllowed(actor, app, at)) throw missing();
            return;
        }
        if (!organizationAllowed(actor, "POINTS_REVIEW", app.acceptingOrganizationId(), at)) {
            if (action == Action.READ && app.status() == Status.APPROVED && correctionAllowed(actor, app, at)) return;
            throw missing();
        }
        if (app.status() == Status.PENDING && !validBasis(app, context, last)) {
            if (action == Action.REVIEW) throw conflict("acceptance-unavailable");
            throw missing();
        }
    }

    private boolean correctionAllowed(long actor, Application app, LocalDateTime at) {
        return targets(actor, "POINTS_CORRECTION", at).stream().anyMatch(t -> "PLATFORM".equals(t.type())
                || "PERSON".equals(t.type()) && app.personId().equals(t.personId())
                || "ORGANIZATION".equals(t.type()) && app.acceptingOrganizationId().equals(t.organizationId()));
    }

    public void ledger(long actor, String person, LocalDateTime at) {
        List<String> membership;
        try {
            membership = persons.completeMemberships(person, at);
        } catch (ContractProblemException e) {
            throw unavailable(Dependency.PERSON_RESOLVER);
        }
        var targets = targets(actor, "POINTS_PERSON_LEDGER", at);
        if (targets.stream().anyMatch(t -> "PLATFORM".equals(t.type()) || "PERSON".equals(t.type()) && person.equals(t.personId())))
            return;
        if (!membership.isEmpty() && targets.stream().anyMatch(t -> "ORGANIZATION".equals(t.type()) && membership.stream().allMatch(o -> o.equals(t.organizationId()))))
            return;
        throw missing();
    }

    public List<UnifiedModels.Target> targets(long actor, String duty, LocalDateTime at) {
        return sources(actor, duty, at).stream().map(SourceTarget::target).toList();
    }

    public List<SourceTarget> sources(long actor, String duty, LocalDateTime at) {
        String owner = duties.managementOwner(authorization.current(actor, at), duty);
        if (owner != null)
            return List.of(new SourceTarget(null, owner, new UnifiedModels.Target("PLATFORM", null, null)));
        return duties.effective(actor, duty, at).stream().map(s -> new SourceTarget(s.id(), s.assignmentId(), duties.decode(s.selection(), UnifiedModels.Selection.class).target())).toList();
    }

    public boolean organizationAllowed(long actor, String duty, String organization, LocalDateTime at) {
        return targets(actor, duty, at).stream().anyMatch(t -> "PLATFORM".equals(t.type()) || "ORGANIZATION".equals(t.type()) && organization.equals(t.organizationId()));
    }
}
