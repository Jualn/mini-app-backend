package cn.jualn.miniapp.module.person.mapper;

import cn.jualn.miniapp.common.web.NameRow;
import cn.jualn.miniapp.module.person.entity.PersonRows.Event;
import cn.jualn.miniapp.module.person.entity.PersonRows.Head;
import cn.jualn.miniapp.module.person.entity.PersonRows.LocalMembership;
import cn.jualn.miniapp.module.person.entity.PersonRows.Membership;
import cn.jualn.miniapp.module.person.entity.PersonRows.Organization;
import cn.jualn.miniapp.module.person.entity.PersonRows.Receipt;
import cn.jualn.miniapp.module.person.entity.PersonRows.Scope;
import cn.jualn.miniapp.module.person.bo.PersonBO.Summary;

import java.time.LocalDateTime;
import java.util.List;

import cn.jualn.miniapp.module.person.service.roster.PartyPersonnelDirectory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PersonMapper {
    long gateShared();

    long currentEpoch();

    long gateExclusive();

    int advanceEpoch(long expected);

    LocalDateTime now();

    Head head(String person);

    Organization organization(String id);

    record PartyOrganizations(boolean platform, List<String> organizationIds, List<String> partyTypes,
                              String type, String q, int limit, long offset) {
    }

    long partyOrganizationCount(PartyOrganizations query);

    List<Organization> partyOrganizations(PartyOrganizations query);

    List<NameRow> organizationNames(@Param("ids") List<String> ids);

    List<Membership> memberships(String person);

    Scope scope(String id);

    List<Scope> actorScopes(long actor);

    Event event(String id);

    Receipt receipt(@Param("actor") long actor, @Param("operation") String operation, @Param("target") String target, @Param("key") String key);

    int insertEvent(Event event);

    int insertReceipt(Receipt receipt);

    int saveHead(Head head);

    int insertHead(Head head);

    int insertOrganization(Organization organization);

    int updateOrganization(Organization organization);

    int insertMembership(Membership membership);

    int endMembership(@Param("id") String id, @Param("event") String event);

    LocalMembership localMembership(@Param("person") String person, @Param("organization") String organization);

    int saveLocalMembership(LocalMembership fact);

    int clearLocalMemberships(String person);

    record OrganizationMembers(String organization, LocalDateTime now, int limit, long offset) {
    }

    long corruptOrganizationMembers(OrganizationMembers query);

    long organizationMemberCount(OrganizationMembers query);

    List<cn.jualn.miniapp.module.person.bo.PersonBO.LocalMembership> organizationMembers(OrganizationMembers query);

    record PartyMembers(String organization, LocalDateTime now, String q, String matchedPerson,
                        String personId, int limit, long offset) {
    }

    record PartyNamedCandidates(boolean platform, List<String> organizations, LocalDateTime now, String name) {
    }

    record PartyNamedCandidate(String personId, String realName) {
    }

    List<PartyNamedCandidate> partyNamedCandidates(PartyNamedCandidates query);

    long partyMemberCount(PartyMembers query);

    List<PartyPersonnelDirectory.MemberRow> partyMembers(PartyMembers query);

    long localHistoryCount(@Param("person") String person, @Param("organization") String organization);

    List<Event> localHistory(@Param("person") String person, @Param("organization") String organization, @Param("limit") int limit, @Param("offset") long offset);

    int insertScope(Scope scope);

    int revokeScope(Scope scope);

    int releaseExpiredScopes(@Param("now") LocalDateTime now);

    long corruptSource();

    long corruptScopes(long actor);

    long corruptPerson(String person);

    long corruptFacts();

    long unknownUsers();

    record Check(String domain, String action) {
    }

    record DutyTarget(String domain, String targetType, String targetId, String actions, String sourceId) {
        public DutyTarget(String domain, String targetType, String targetId) {
            this(domain, targetType, targetId, "READ,MAINTAIN", null);
        }
    }

    record Query(long actor, List<Check> checks, LocalDateTime now, String personId, String organizationId, int limit,
                 long offset, boolean rootOwner, List<DutyTarget> dutyTargets, String q, String status) {
        public Query(long actor, List<Check> checks, LocalDateTime now, String personId, String organizationId, int limit, long offset, boolean rootOwner, List<DutyTarget> dutyTargets) {
            this(actor, checks, now, personId, organizationId, limit, offset, rootOwner, dutyTargets, null, null);
        }

        public Query withFilters(String q, String status) {
            return new Query(actor, checks, now, personId, organizationId, limit, offset, rootOwner, dutyTargets, q, status);
        }

        public Query(long actor, List<Check> checks, LocalDateTime now, String personId, String organizationId, int limit, long offset, boolean rootOwner) {
            this(actor, checks, now, personId, organizationId, limit, offset, rootOwner, List.of());
        }

        public Query(long actor, List<Check> checks, LocalDateTime now, String personId, String organizationId, int limit, long offset) {
            this(actor, checks, now, personId, organizationId, limit, offset, false);
        }

        public Query {
            checks = List.copyOf(checks);
            dutyTargets = List.copyOf(dutyTargets);
        }
    }

    long allowed(Query query);

    long indeterminateCandidates(Query query);

    long indeterminatePointsCandidates(Query query);

    long pointsOrganizationVisible(Query query);

    long personCount(Query query);

    List<Summary> personPage(Query query);

    List<cn.jualn.miniapp.module.person.bo.PersonBO.OrganizationGroup> personGroups(Query query);

    long organizationCount(@Param("dutyOrganizations") List<String> dutyOrganizations, @Param("actor") long actor, @Param("rootOwner") boolean rootOwner, @Param("now") LocalDateTime now, @Param("status") String status);

    List<Organization> organizationPage(@Param("dutyOrganizations") List<String> dutyOrganizations, @Param("actor") long actor, @Param("rootOwner") boolean rootOwner, @Param("now") LocalDateTime now, @Param("status") String status, @Param("limit") int limit, @Param("offset") long offset);

    long historyCount(@Param("person") String person, @Param("identity") boolean identity);

    List<Event> history(@Param("person") String person, @Param("identity") boolean identity, @Param("limit") int limit, @Param("offset") long offset);

    long scopeCount(@Param("actor") long actor, @Param("rootOwner") boolean rootOwner, @Param("now") LocalDateTime now, @Param("grantee") Long grantee, @Param("domain") String domain);

    List<Scope> scopePage(@Param("actor") long actor, @Param("rootOwner") boolean rootOwner, @Param("now") LocalDateTime now, @Param("grantee") Long grantee, @Param("domain") String domain, @Param("limit") int limit, @Param("offset") long offset);
}
