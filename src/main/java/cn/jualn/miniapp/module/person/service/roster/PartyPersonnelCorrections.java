package cn.jualn.miniapp.module.person.service.roster;

import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;
import cn.jualn.miniapp.module.person.service.facts.PersonFoundationService;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonAccess;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonIdentifierService;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonService;
import cn.jualn.miniapp.module.person.service.support.PersonPrivateData;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.invalid;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.problem;

@Service
@RequiredArgsConstructor
class PartyPersonnelCorrections {
    private final RosterAccess access;
    private final ManagedPersonAccess personAccess;
    private final PersonFactReaders readers;
    private final ManagedPersonMapper persons;
    private final ManagedPersonService profiles;
    private final ManagedPersonIdentifierService identifiers;
    private final PersonFoundationService foundation;
    private final PersonCatalog catalog;
    private final PersonPrivateData secrets;

    List<String> editable(long actor, String organization, String person, PersonReadContext context) {
        if (!canCorrect(actor, organization, person, context, false)) return List.of();
        var fields = new ArrayList<>(List.of("realName", "majorClass"));
        if (canSupplement(person)) fields.add("studentNumber");
        var identity = readers.identity(person, context);
        if (identity.outcome() == PersonFactReaders.Outcome.KNOWN
                && ("ACTIVE".equals(identity.status()) || "NONE".equals(identity.presence()))) fields.add("stageId");
        return fields;
    }

    private boolean canCorrect(long actor, String organization, String person, PersonReadContext context, boolean finalCheck) {
        var profile = persons.profile(person);
        if (profile == null || !"ACTIVE".equals(profile.getStatus()) || persons.everBound(person, actor)) return false;
        LocalDateTime at = finalCheck ? context.finalEvaluatedAt() : context.evaluatedAt();
        if (!access.allows(actor, organization, "MAINTAIN_PROFILE", at)) return false;
        var local = finalCheck ? readers.localMembershipFinal(person, organization, context)
                : readers.localMembership(person, organization, context);
        if (local.outcome() != PersonFactReaders.Outcome.KNOWN || !"PRESENT".equals(local.fact().presence())) return false;
        if (personAccess.optionalWrite(actor, person, at)) return true;
        if(access.coversRecordedPersonnel(actor,person,at)) return true;
        var membership = finalCheck ? readers.membershipsFinal(person, context) : readers.memberships(person, context);
        return membership.outcome() == PersonFactReaders.Outcome.KNOWN && membership.organizationIds().contains(organization)
                && access.coversSharedPersonnel(actor, membership.organizationIds(), at);
    }

    private boolean canSupplement(String person) {
        return persons.personBinding(person) == null && persons.identifiers(person).stream().noneMatch(identifier ->
                identifiers.rosterNamespace().equals(identifier.getNamespace()) && "ACTIVE".equals(identifier.getStatus()));
    }

    Map<String, String> prepare(long actor, String organization, String person, Map<String, String> input,
                                PersonReadContext context) {
        if (input == null || input.isEmpty()) throw invalid("fields");
        var allowed = editable(actor, organization, person, context);
        var values = new LinkedHashMap<String, String>();
        for (var field : input.keySet().stream().sorted().toList()) {
            if (!allowed.contains(field)) throw problem(HttpStatus.FORBIDDEN, "forbidden");
            var raw = input.get(field);
            switch (field) {
                case "realName" -> {
                    var value = ManagedPersonService.text(raw, 100, field);
                    if (!value.equals(persons.profile(person).getRealName())) values.put(field, value);
                }
                case "majorClass" -> {
                    var value = ManagedPersonService.nullable(raw, 200, field);
                    var registration = persons.registration(person);
                    if (!Objects.equals(value, registration == null ? null : registration.getMajorClass())) values.put(field, value);
                }
                case "stageId" -> {
                    var value = ManagedPersonService.text(raw, 64, field);
                    if (!catalog.confirmed(value)) throw problem(HttpStatus.CONFLICT, "person-stage-unconfirmed");
                    if (!value.equals(readers.identity(person, context).stageId())) values.put(field, value);
                }
                case "studentNumber" -> {
                    var value = ManagedPersonService.text(raw, 100, field);
                    if (identifiers.rosterPerson(value) != null) throw problem(HttpStatus.CONFLICT, "person-identifier-conflict");
                    values.put(field, value);
                }
                default -> throw invalid("fields");
            }
        }
        if (values.isEmpty()) throw invalid("fields");
        return values;
    }

    void apply(long actor, String organization, String person, Map<String, String> values, String reason,
               String reference, String key, PersonReadContext context) {
        if (!canCorrect(actor, organization, person, context, false)) throw problem(HttpStatus.FORBIDDEN, "forbidden");
        for (var entry : values.entrySet()) {
            var childKey = secrets.digest("party-correction-field", key + ":" + entry.getKey());
            switch (entry.getKey()) {
                case "realName" -> profiles.correctNameFromPartyPlan(actor, person, entry.getValue(), reason, reference, childKey, context);
                case "majorClass" -> profiles.correctClassFromPartyPlan(actor, person, entry.getValue(), reason, reference, childKey, context);
                case "stageId" -> foundation.correctStageFromPartyPlan(actor, person, entry.getValue(), reason, reference, childKey, context);
                case "studentNumber" -> {
                    if (!canSupplement(person)) throw problem(HttpStatus.CONFLICT, "person-identifier-conflict");
                    identifiers.supplementFromPartyPlan(actor, person, entry.getValue(), reason, reference, childKey, context);
                }
                default -> throw invalid("fields");
            }
        }
        ManagedPersonService.recheckBeforeCommit(() -> {
            readers.beginFinalCheck(context);
            if (!canCorrect(actor, organization, person, context, true)) throw problem(HttpStatus.FORBIDDEN, "forbidden");
        });
    }
}
