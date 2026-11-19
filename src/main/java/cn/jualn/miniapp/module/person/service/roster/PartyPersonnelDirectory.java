package cn.jualn.miniapp.module.person.service.roster;

import cn.jualn.miniapp.module.authorization.model.RbacModels.Source;
import cn.jualn.miniapp.module.authorization.service.TransactionalAuthorization;
import cn.jualn.miniapp.module.person.mapper.PersonMapper;
import cn.jualn.miniapp.module.person.mapper.ManagedPersonMapper;
import cn.jualn.miniapp.module.person.service.profile.ManagedPersonIdentifierService;
import cn.jualn.miniapp.module.person.service.facts.PersonReadContext;
import cn.jualn.miniapp.module.person.service.facts.PersonFactReaders;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static cn.jualn.miniapp.module.person.PersonProblems.denied;
import static cn.jualn.miniapp.module.person.PersonProblems.invalid;
import static cn.jualn.miniapp.module.person.PersonProblems.missing;
import static cn.jualn.miniapp.module.person.PersonProblems.unavailable;
import static cn.jualn.miniapp.module.person.service.roster.RosterAccess.PARTY_ORGANIZATION_TYPES;
import static cn.jualn.miniapp.module.person.service.support.PersonnelValues.page;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class PartyPersonnelDirectory {

    private final PartyPersonnelCorrections corrections;
    private final PersonFactReaders readers;
    private final PersonMapper mapper;
    private final RosterAccess access;
    private final TransactionalAuthorization authorization;
    private final ManagedPersonMapper persons;
    private final ManagedPersonIdentifierService identifiers;

    public record Organization(String organizationId, String name, String type) {
    }

    public record OrganizationPage(List<Organization> items, int page, int pageSize, long totalItems,
                                   long authorizedOrganizationCount, Instant evaluatedAt) {
    }

    public record MemberRow(String personId, String membershipId, String organizationId, String realName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Member(String personId, String membershipId, String organizationId, String realName,
                         String studentNumberMasked, String studentNumber, String majorClass, String stageId,
                         List<String> editableFields, boolean canRemove) {
    }

    public record MemberPage(List<Member> items, int page, int pageSize, long totalItems, Instant evaluatedAt) {
    }

    public MemberPage members(long actor, String organization, int pageNumber, int pageSize, String search) {
        page(pageNumber, pageSize);
        if (search != null && (search.isBlank() || search.codePointCount(0, search.length()) > 100)) throw invalid("q");
        var context = memberContext(actor, organization);
        var at = context.evaluatedAt();
        var query = new PersonMapper.PartyMembers(organization, at, search == null ? null : search.strip(),
                search == null ? null : matchingPerson(search), null, pageSize, (long) (pageNumber - 1) * pageSize);
        long total = mapper.partyMemberCount(query);
        var items = mapper.partyMembers(query).stream().map(row -> member(actor, row, context)).toList();
        return new MemberPage(items, pageNumber, pageSize, total, at.toInstant(ZoneOffset.UTC));
    }

    public Member member(long actor, String organization, String person) {
        var context = memberContext(actor, organization);
        var rows = mapper.partyMembers(new PersonMapper.PartyMembers(organization, context.evaluatedAt(),
                null, null, person, 1, 0));
        if (rows.isEmpty()) throw missing();
        return member(actor, rows.get(0), context);
    }

    private PersonReadContext memberContext(long actor, String organization) {
        var context = readers.open(actor);
        var at = context.evaluatedAt();
        access.requireOrganization(actor, organization, false, at);
        if (mapper.corruptOrganizationMembers(new PersonMapper.OrganizationMembers(organization, at, 1, 0)) != 0)
            throw unavailable();
        return context;
    }

    private String matchingPerson(String search) {
        try {
            return identifiers.rosterPerson(search);
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException e) {
            if ("/problems/person-identifier-conflict".equals(e.getType())) return null;
            throw e;
        }
    }

    private Member member(long actor, MemberRow row, PersonReadContext context) {
        var registration = persons.registration(row.personId());
        var identity = readers.identity(row.personId(), context);
        var number = persons.identifiers(row.personId()).stream().filter(identifier ->
                identifiers.rosterNamespace().equals(identifier.getNamespace()) && "ACTIVE".equals(identifier.getStatus())
                        && "VERIFIED".equals(identifier.getVerification())).findFirst().orElse(null);
        return new Member(row.personId(), row.membershipId(), row.organizationId(), row.realName(),
                number == null ? null : number.getValueMasked(), number == null ? null : identifiers.rosterValue(number),
                registration == null ? null : registration.getMajorClass(),
                "ACTIVE".equals(identity.status()) ? identity.stageId() : null, corrections.editable(actor, row.organizationId(), row.personId(), context), !persons.everBound(row.personId(), actor));
    }

    public OrganizationPage organizations(long actor, int pageNumber, int pageSize, String type, String search) {
        page(pageNumber, pageSize);
        if (type != null && !PARTY_ORGANIZATION_TYPES.contains(type)) throw invalid("type");
        if (search != null && (search.isBlank() || search.codePointCount(0, search.length()) > 100)) throw invalid("q");

        var context = readers.open(actor);
        var at = context.evaluatedAt();
        access.capabilities(actor, false, at);
        var decision = authorization.current(actor, at);
        if (decision.source() != Source.ASSIGNMENT || !decision.accountEligible()) throw denied();

        boolean platform = RosterAccess.platform(decision);
        var organizationIds = RosterAccess.organizationIds(decision);
        if (!platform && organizationIds.isEmpty()) throw denied();

        var query = new PersonMapper.PartyOrganizations(platform, organizationIds, PARTY_ORGANIZATION_TYPES,
                type, search == null ? null : search.strip(), pageSize, (long) (pageNumber - 1) * pageSize);
        long total = mapper.partyOrganizationCount(query);
        long authorized = type == null && search == null ? total
                : mapper.partyOrganizationCount(new PersonMapper.PartyOrganizations(
                platform, organizationIds, PARTY_ORGANIZATION_TYPES, null, null, pageSize, 0));
        var items = mapper.partyOrganizations(query).stream()
                .map(row -> new Organization(row.getId(), row.getName(), row.getType())).toList();

        return new OrganizationPage(items, pageNumber, pageSize, total, authorized, at.toInstant(ZoneOffset.UTC));
    }

}
