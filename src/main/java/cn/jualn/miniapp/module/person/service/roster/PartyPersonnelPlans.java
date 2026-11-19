package cn.jualn.miniapp.module.person.service.roster;

import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.person.bo.QualificationBO;
import cn.jualn.miniapp.module.person.bo.QualificationBO.ParsedRow;
import cn.jualn.miniapp.module.person.bo.QualificationBO.PrivateValues;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.service.access.PersonAuthorityGate;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.support.PersonPrivateData;
import cn.jualn.miniapp.module.person.service.support.PersonnelJournal;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.decode;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.encode;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.id;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.instant;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.invalid;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.one;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.text;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class PartyPersonnelPlans {
    private static final String COMMIT = "commitPartyPersonnelChanges";
    private final PartyRosterService roster;
    private final RosterAccess access;
    private final PersonFactReaders readers;
    private final PersonMapper facts;
    private final ManagedPersonMapper persons;
    private final PersonPrivateData secrets;
    private final PersonnelJournal journal;
    private final TransactionalAuthorization authorization;
    private final PersonFoundationService foundation;
    private final PersonCatalog catalog;

    private final PartyPersonnelCorrections corrections;
    public record MatchRequest(String realName, String studentNumber, String majorClass, String stageId,
                               String personId, Boolean confirmNew, Boolean keepCurrentValues) {}
    public record Candidate(String personId, String realName) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Match(String outcome, String matchToken, String issue, String membershipAction, List<Candidate> candidates, java.util.Map<String, Object> current) {}
    public record Item(String action, String matchToken, String personId, String membershipId,
                       String targetOrganizationId, Map<String, String> fields) {}
    public record RowCorrection(int rowNumber, String personId, Map<String, String> fields) {}
    public record Workbook(String importId, String parsedPlanId, String version, List<Integer> skipRows,
                           QualificationBO.ReviewDecision review, List<Integer> keepCurrentRows, List<RowCorrection> corrections) {}
    public record Request(String kind, List<Item> items, Workbook workbook) {}
    public record Confirm(String reason, String verificationReference, String planToken) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Effect(int sequence, String organizationId, String targetOrganizationId, String displayName,
                         List<String> actions, List<String> correctedFields, Map<String, String> changes) {
        public Effect(int sequence, String organizationId, String targetOrganizationId, String displayName, List<String> actions) {
            this(sequence, organizationId, targetOrganizationId, displayName, actions, null, null);
        }
    }
    public record Totals(long created, long reused, long corrected, long joined, long removed, long unchanged, long skipped) {}
    public record Plan(String planId, java.time.Instant expiresAt, Totals totals, List<Effect> effects, String planToken) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Receipt(String planId, java.time.Instant committedAt, Totals totals, List<String> affectedOrganizationIds,
                          String importId) {}

    private record Matched(long actor, String organization, MatchRequest input, LocalDateTime expiresAt, String version) {}
    private record Fixed(long actor, String organization, String planId, Request request, List<String> organizations,
                         LocalDateTime expiresAt, String version) {}
    private record Draft(List<Resolved> items, List<String> organizations, List<Effect> effects, String version) {}
    private record Resolved(Item item, MatchRequest input, String person, Effect effect) {}
    private record TargetCycle(String organization, String person, String cycle) {}

    public Match match(long actor, String organization, MatchRequest request) {
        var c = readers.open(actor);
        access.requireOrganization(actor, organization, true, c.evaluatedAt());
        var input = normalize(request);
        return match(actor, organization, input, c, true);
    }

    private Match match(long actor, String organization, MatchRequest input, PersonReadContext c, boolean issueToken) {
        var at = c.evaluatedAt();
        if (input.personId() != null && !visible(actor, organization, input.personId(), at)) throw notFound();
        var resolution = roster.matchRegistration(actor, organization, row(input), input.personId(), at);
        if ("BLOCKED".equals(resolution.outcome())) return new Match("UNRESOLVED", null, "UNRESOLVED", null, null, null);
        boolean created = roster.registrationPerson(input.studentNumber(), input.personId()) == null;
        if (created) {
            var possible = roster.namedCandidates(actor, input.realName(), at);
            var candidates = possible.stream().limit(20)
                    .map(member -> new Candidate(member.personId(), member.realName())).toList();
            if (!candidates.isEmpty() && (possible.size() > 20 || !Boolean.TRUE.equals(input.confirmNew()) || input.studentNumber() == null
                    || candidates.stream().anyMatch(candidate -> !roster.hasTrustedNumber(candidate.personId()))))
                return new Match("UNRESOLVED", null, "UNRESOLVED", null, candidates, null);
        }
        var result = roster.personnelMatch(row(input), resolution, input.personId(), c);
        var token = issueToken && (!"DIFFERS".equals(result.outcome()) || Boolean.TRUE.equals(input.keepCurrentValues())) ? seal("party-match", new Matched(actor, organization, input, at.plusMinutes(10), version(actor, c))) : null;
        return new Match(result.outcome(), token, null, resolution.membershipAction(), null, result.current());
    }

    public Plan preview(long actor, String organization, Request request) {
        var c = readers.open(actor);
        if (request != null && Set.of("INCREMENTAL", "FULL_REVIEW").contains(request.kind() == null ? "" : request.kind())) {
            if (request.items() != null || request.workbook() == null
                    || ("FULL_REVIEW".equals(request.kind()) != (request.workbook().review() != null))) throw invalid("workbook");
            var workbook = roster.previewWorkbook(actor, organization, request.workbook(), c);
            var planId = id();
            var fixed = new Fixed(actor, organization, planId, request, List.of(organization), workbook.expires(),
                    workbook.fingerprint());
            return new Plan(planId, instant(workbook.expires()), totals(workbook.effects()), workbook.effects(), seal("party-plan", fixed));
        }
        var draft = draft(actor, organization, request, c);
        var planId = id();
        var expires = c.evaluatedAt().plusMinutes(10);
        var fixed = new Fixed(actor, organization, planId, request, draft.organizations(), expires, draft.version());
        return new Plan(planId, instant(expires), totals(draft.effects()), draft.effects(), seal("party-plan", fixed));
    }

    public Receipt commit(long actor, String organization, String planId, Confirm command, String key) {
        if (command == null) throw invalid("body");
        var reason = text(command.reason(), 2000, "reason");
        var reference = command.verificationReference() == null ? planId : text(command.verificationReference(), 256, "verificationReference");
        var c = readers.open(PersonAuthorityGate.Access.EXCLUSIVE, actor);
        var at = c.evaluatedAt();
        access.requireOrganization(actor, organization, true, at);
        var fixed = open("party-plan", command.planToken(), Fixed.class);
        if (actor != fixed.actor() || !organization.equals(fixed.organization()) || !planId.equals(fixed.planId())) throw stale();
        fixed.organizations().forEach(org -> access.requireOrganization(actor, org, true, at));
        var replay = journal.replay(actor, COMMIT, planId, key, command, Receipt.class);
        if (replay != null) return replay.value();
        if (!fixed.expiresAt().isAfter(at) || facts.event(planId) != null) throw stale();
        if (fixed.request().workbook() != null) {
            var workbook = fixed.request().workbook();
            var evaluated = roster.previewWorkbook(actor, organization, workbook, c);
            if (!fixed.version().equals(evaluated.fingerprint())) throw stale();
            if (workbook.review() != null && command.verificationReference() == null) throw invalid("verificationReference");
            var payload = new QualificationBO.Commit(reason, reference, true, workbook.parsedPlanId(),
                    workbook.skipRows(), workbook.review());
            var importKey = secrets.digest("party-personnel-workbook", planId);
            roster.commitInContext(actor, workbook.importId(), payload, importKey, evaluated.etag(), c, evaluated.corrections());
            var receipt = new Receipt(planId, instant(at), totals(evaluated.effects()), fixed.organizations(), workbook.importId());
            journal.append(actor, COMMIT, planId, key, command, planId, "ROSTER_TRACE", 1, null, receipt,
                    reason, reference, "LOCAL_MANUAL", null, null, null, at, 200, null);
            return receipt;
        }
        var draft = draft(actor, organization, fixed.request(), c);
        if (!fixed.version().equals(draft.version())) throw stale();

        var before = new HashMap<String, cn.jualn.miniapp.module.person.bo.PersonBO.RosterCompleteness>();
        draft.organizations().forEach(org -> before.put(org, foundation.partyRosterBefore(org, at)));
        var checkedPeople = new HashSet<String>();
        var targetCycles = new ArrayList<TargetCycle>();
        for (var resolved : draft.items()) {
            var item = resolved.item();
            var child = secrets.digest("party-personnel-item", planId + ":" + resolved.effect().sequence());
            var source = "manual:" + planId + ":" + resolved.effect().sequence();
            String person = resolved.person();
            if ("REGISTER".equals(item.action())) {
                person = roster.register(actor, organization, row(resolved.input()), resolved.input().personId(), source, 1, child, c);
            } else if ("CORRECT".equals(item.action())) {
                corrections.apply(actor, organization, person, resolved.effect().changes(), reason, reference, child, c);
                targetCycles.add(new TargetCycle(organization, person, item.membershipId()));
            } else {
                if (item.targetOrganizationId() != null)
                    foundation.initializeRosterPerson(actor, person, item.targetOrganizationId(), null, source, 1, actor, child, false, c);
                if ("REMOVE".equals(item.action()) || "TRANSFER".equals(item.action()))
                    foundation.removeReviewedMember(actor, organization, person, item.membershipId(), reason, reference, child, c);
            }
            var destination = "REGISTER".equals(item.action()) ? organization : item.targetOrganizationId();
            if (destination != null) targetCycles.add(new TargetCycle(destination, person, roster.currentRegisteredCycle(destination, person, at)));
            checkedPeople.add(person);
        }
        var receipt = new Receipt(planId, instant(at), totals(draft.effects()), draft.organizations(), null);
        journal.append(actor, COMMIT, planId, key, command, planId, "ROSTER_TRACE", 1, null, receipt,
                reason, reference, "LOCAL_MANUAL", null, null, null, at, 200, null);
        before.forEach((org, proof) -> foundation.finishPartyRoster(actor, proof, planId, false, at));
        one(facts.advanceEpoch(Long.parseLong(c.authorityVersion())));
        cn.jualn.miniapp.module.person.service.profile.ManagedPersonService.recheckBeforeCommit(() -> {
            var now = facts.now();
            draft.organizations().forEach(org -> access.requireOrganization(actor, org, true, now));
            for (var cycle : targetCycles)
                if (!cycle.cycle().equals(roster.currentRegisteredCycle(cycle.organization(), cycle.person(), now))) throw stale();
            for (var person : checkedPeople)
                if (persons.everBound(person, actor)) throw problem(HttpStatus.FORBIDDEN, "person-self-governance-forbidden");
        });
        return receipt;
    }

    private Draft draft(long actor, String organization, Request request, PersonReadContext c) {
        var at = c.evaluatedAt();
        access.requireOrganization(actor, organization, true, at);
        if (request == null || !"MANUAL".equals(request.kind()) || request.items() == null
                || request.items().isEmpty() || request.items().size() > 100 || request.workbook() != null) throw invalid("items");
        var rows = new ArrayList<Resolved>();
        var organizations = new java.util.TreeSet<String>();
        organizations.add(organization);
        var subjects = new HashSet<String>();
        var newNames = new HashSet<String>();
        for (var item : request.items()) {
            if (item == null || item.action() == null) throw invalid("items");
            if ("REGISTER".equals(item.action())) {
                if (item.personId() != null || item.membershipId() != null || item.fields() != null || item.targetOrganizationId() != null)
                    throw invalid("items");
                var matched = open("party-match", item.matchToken(), Matched.class);
                if (matched.actor() != actor || !organization.equals(matched.organization()) || !matched.expiresAt().isAfter(at)
                        || !matched.version().equals(version(actor, c))) throw stale();
                var input = matched.input();
                var match = match(actor, organization, input, c, false);
                if ("UNRESOLVED".equals(match.outcome()) || "DIFFERS".equals(match.outcome()) && !Boolean.TRUE.equals(input.keepCurrentValues())) throw stale();
                var person = roster.registrationPerson(input.studentNumber(), input.personId());
                if (person == null && (!newNames.add(input.realName()) || input.studentNumber() != null
                        && !subjects.add("number:" + input.studentNumber())) || person != null && !subjects.add(person))
                    throw invalid("items");
                var effect = new Effect(rows.size()+1, organization, null, input.realName(), List.of(person == null ? "CREATE" : "REUSE",
                        "ADD".equals(match.membershipAction()) ? "JOIN" : "UNCHANGED"));
                rows.add(new Resolved(item, input, person, effect));
                continue;
            }
            if (!Set.of("REMOVE", "ADD_AFFILIATION", "TRANSFER", "CORRECT").contains(item.action())
                    || item.matchToken() != null) throw invalid("items.action");
            boolean correction = "CORRECT".equals(item.action());
            if (correction != (item.fields() != null)) throw invalid("items.fields");
            var person = text(item.personId(), 128, "personId");
            var member = text(item.membershipId(), 128, "membershipId");
            if (!subjects.add(person)) throw invalid("items");
            var current = facts.partyMembers(new PersonMapper.PartyMembers(organization, at, null, null, person, 1, 0));
            if (current.isEmpty() || !member.equals(current.get(0).membershipId())) throw notFound();
            if (persons.everBound(person, actor)) throw problem(HttpStatus.FORBIDDEN, "person-self-governance-forbidden");
            if (correction) {
                if (item.targetOrganizationId() != null) throw invalid("targetOrganizationId");
                var changes = corrections.prepare(actor, organization, person, item.fields(), c);
                if (changes.containsKey("studentNumber") && !subjects.add("number:" + changes.get("studentNumber")))
                    throw invalid("items.fields");
                rows.add(new Resolved(item, null, person, new Effect(rows.size()+1, organization, null,
                        current.get(0).realName(), List.of("CORRECT"), List.copyOf(changes.keySet()), changes)));
                continue;
            }
            var affected = new ArrayList<String>();
            var target = item.targetOrganizationId();
            if ("REMOVE".equals(item.action())) {
                if (target != null) throw invalid("targetOrganizationId");
            } else {
                var profile = persons.profile(person);
                if (profile == null || !"ACTIVE".equals(profile.getStatus())) throw stale();
                text(target, 128, "targetOrganizationId");
                if (organization.equals(target)) throw invalid("targetOrganizationId");
                access.requireOrganization(actor, target, true, at);
                organizations.add(target);
                var found = facts.memberships(person).stream().filter(m -> target.equals(m.getOrganizationId())).toList();
                boolean present = found.stream().anyMatch(m -> !m.getValidFrom().isAfter(at)
                        && (m.getValidUntil() == null || m.getValidUntil().isAfter(at)));
                if (!present && found.stream().anyMatch(m -> m.getValidFrom().isAfter(at))) throw stale();
                affected.add(present ? "UNCHANGED" : "JOIN");
            }
            if (!"ADD_AFFILIATION".equals(item.action())) {
                affected.remove("UNCHANGED");
                affected.add("REMOVE");
            }
            rows.add(new Resolved(item, null, person,
                    new Effect(rows.size()+1, organization, target, current.get(0).realName(), affected)));
        }
        var effects = rows.stream().map(Resolved::effect).toList();
        var resolvedPeople = rows.stream().map(Resolved::person).toList();
        var digest = secrets.digest("party-manual-plan", encode(List.of(version(actor, c), effects, resolvedPeople)));
        return new Draft(List.copyOf(rows), List.copyOf(organizations), effects, digest);
    }

    private boolean visible(long actor, String organization, String person, LocalDateTime at) {
        var profile = persons.profile(person);
        if (profile == null || !"ACTIVE".equals(profile.getStatus())) return false;
        return facts.memberships(person).stream().map(m -> m.getOrganizationId()).distinct()
                .filter(org -> organization.equals(org) || access.organization(actor, org, false, at))
                .anyMatch(org -> !facts.partyMembers(new PersonMapper.PartyMembers(org, at, null, null, person, 1, 0)).isEmpty());
    }

    private MatchRequest normalize(MatchRequest body) {
        if (body == null) throw invalid("body");
        var stage = optional(body.stageId(), 64, "stageId");
        if (stage != null && !catalog.confirmed(stage)) throw invalid("stageId");
        return new MatchRequest(text(body.realName(), 100, "realName"), optional(body.studentNumber(), 100, "studentNumber"),
                optional(body.majorClass(), 200, "majorClass"), stage, optional(body.personId(), 128, "personId"), body.confirmNew(), body.keepCurrentValues());
    }

    private String optional(String value, int max, String field) {
        return value == null ? null : text(value, max, field);
    }

    private ParsedRow row(MatchRequest input) {
        return new ParsedRow(1, new PrivateValues(input.realName(), input.studentNumber(), input.majorClass()), input.stageId(), List.of());
    }

    private String version(long actor, PersonReadContext c) {
        return secrets.digest("party-authority-version", encode(List.of(c.authorityVersion(),
                authorization.current(actor, c.evaluatedAt()).authorizationVersion(), roster.namespace())));
    }

    private String seal(String purpose, Object data) {
        var encrypted = Base64.getUrlEncoder().withoutPadding().encodeToString(
                secrets.encrypt(purpose, encode(data).getBytes(StandardCharsets.UTF_8)));
        if (encrypted.length() + 65 > tokenLimit(purpose)) throw invalid("plan");
        return encrypted + "." + secrets.digest(purpose, encrypted);
    }

    private <T> T open(String purpose, String token, Class<T> type) {
        if (token == null || token.length() > tokenLimit(purpose)) throw stale();
        int split = token.lastIndexOf('.');
        if (split < 1 || token.length() != split + 65) throw stale();
        var payload = token.substring(0, split);
        if (!MessageDigest.isEqual(token.substring(split+1).getBytes(StandardCharsets.UTF_8),
                secrets.digest(purpose, payload).getBytes(StandardCharsets.UTF_8))) throw stale();
        return decode(new String(secrets.decrypt(purpose, Base64.getUrlDecoder().decode(payload)), StandardCharsets.UTF_8), type);
    }

    private int tokenLimit(String purpose) {
        return "party-match".equals(purpose) ? 4096 : 15_000_000;
    }

    private Totals totals(List<Effect> effects) {
        return new Totals(count(effects,"CREATE"), count(effects,"REUSE"), count(effects,"CORRECT"), count(effects,"JOIN"),
                count(effects,"REMOVE"), count(effects,"UNCHANGED"), count(effects,"SKIP"));
    }

    private long count(List<Effect> effects, String action) {
        return effects.stream().filter(effect -> effect.actions().contains(action)).count();
    }

    private RuntimeException stale() { return problem(HttpStatus.CONFLICT, "person-plan-stale"); }
    private RuntimeException notFound() { return problem(HttpStatus.NOT_FOUND, "not-found"); }
}
