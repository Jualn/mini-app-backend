package cn.jualn.miniapp.module.person.service.access;

import cn.jualn.miniapp.module.person.mapper.DutyCandidateMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/** Minimal directory projection after the authorization owner proves a single source and intent. */
@Service @RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class DutyCandidateDirectory {
    public record Ceiling(String type, String personId, String organizationId) {}
    public record TargetRow(String type, String personId, String organizationId, String label, String distinguishingLabel) {}
    public record Candidate(String candidateId, Long userId, String nickname, String personId, String realName, String personStatus) {}
    public record Organization(String personId, String organizationId, String name, String distinguishingLabel) {}
    private final DutyCandidateMapper mapper;
    private final PersonBusinessScopes scopes;
    public List<TargetRow> targets(List<Ceiling> ceilings, List<String> types, boolean historical, boolean partyOnly,
                                   String platformLabel, String q, long offset, int limit) {
        return mapper.targets(ceilings, types, historical, partyOnly, platformLabel, q, offset, limit);
    }
    public List<Candidate> recipients(boolean independent, String memberOrganization, String filter, String q,
                                      LocalDateTime at, long offset, int limit) {
        scopes.verifyDirectoryFacts();
        return mapper.recipients(independent, memberOrganization, filter, q, at, offset, limit);
    }

    public List<Organization> organizations(List<String> persons, List<String> visible, LocalDateTime at) {
        scopes.verifyDirectoryFacts();
        return persons.isEmpty()?List.of():mapper.organizations(persons,visible,at);
    }
    public LocalDateTime nextBoundary(LocalDateTime at) {return mapper.nextBoundary(at);}
}
