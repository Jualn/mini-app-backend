package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.*;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.person.service.access.LearningRecipientReader;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import cn.jualn.miniapp.module.person.service.facts.PersonCatalog;
import cn.jualn.miniapp.module.article.service.ArticleCursor;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.*;

@Service
@RequiredArgsConstructor
public class LearningTaskRecipientOptions {
    private final LearningTaskAuthorizationPolicy authorization;
    private final LearningTaskScopePolicy scopes;
    private final PersonBusinessScopes persons;
    private final PersonCatalog catalog;
    private final LearningTaskResponsibilities responsibilities;
    private final LearningRecipientReader candidates;

    public record Organization(String organizationId, String displayName) {
    }

    public record Stage(String stageId, String displayName) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Display(String displayName, List<Organization> organizations, Stage developmentStage,
                          String disambiguation) {
        public Display(String displayName, List<Organization> organizations, Stage developmentStage) {
            this(displayName, organizations, developmentStage, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Option(String type, String id, Display display, boolean selectable, String unavailableReason) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(List<Option> items, String nextCursor) {
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Page list(Long task, String targetType, String org, String type, String q, List<String> orgs, List<String> stages, String cursor, int size) {
        long actor = authorization.authorize(Action.PREVIEW, task, null);
        authorization.authorize(Action.EDIT, task, null);
        Target target = scopes.optionTarget(actor, task, targetType, org);
        if (!Set.of("PERSON", "ACCOUNT", "ORGANIZATION", "DEVELOPMENT_STAGE").contains(type) || size < 1 || size > 50 ||
                q != null && (q.isBlank() || q.codePointCount(0, q.length()) > 120)) throw invalid("query");
        validateIds(orgs);
        validateIds(stages);
        if (!Set.of("PERSON", "ACCOUNT").contains(type) && (!orgs.isEmpty() || !stages.isEmpty()))
            throw invalid("query");
        var ceilings = scopes.optionCeilings(actor, target);
        boolean platform = ceilings.stream().anyMatch(t -> "PLATFORM".equals(t.type()));
        var visibleOrgs = ceilings.stream().filter(t -> "ORGANIZATION".equals(t.type())).map(Target::organizationId).distinct().sorted().toList();
        if (!platform && !visibleOrgs.containsAll(orgs)) throw missing();
        for (String requested : orgs) persons.requireOrganization(requested);
        persons.requireDevelopmentStages(stages);
        String authorityBinding = scopes.articleOptionsVersion(actor, task, targetType, org);
        String context = ArticleCursor.fingerprint(Arrays.asList("learning-recipients-v1", type, q, orgs, stages, authorityBinding));
        String after = ArticleCursor.decode(cursor, context);
        List<Option> result = new ArrayList<>();
        String last = null;
        boolean more = false;
        if (Set.of("PERSON", "ACCOUNT").contains(type)) {
            var rows = candidates.candidates("ACCOUNT".equals(type), ceilings, orgs, stages, q, after, size + 1, persons.now());
            more = rows.size() > size;
            for (var row : rows.stream().limit(size).toList()) {
                last = row.id();
                if (!scopes.optionCovered(type, row.id(), ceilings)) continue;
                if ("ACCOUNT".equals(type) && row.personId() != null && !row.personId().equals(responsibilities.accountPerson(Long.parseLong(row.id()))))
                    continue;
                List<Organization> organizations = new ArrayList<>();
                Stage stage = null;
                if (row.personId() != null) {
                    var positive = persons.positiveOrganizations(row.personId(), persons.now());
                    if (!orgs.isEmpty() && positive.stream().noneMatch(orgs::contains)) continue;
                    for (String o : positive)
                        if (platform || visibleOrgs.contains(o))
                            organizations.add(new Organization(o, persons.organizationName(o)));
                    String stageId = displayStage(row.personId(), !stages.isEmpty());
                    if (!stages.isEmpty() && !stages.contains(stageId)) continue;
                    stage = catalog.value().stages().stream().filter(s -> s.stageId().equals(stageId) && catalog.confirmed(s.stageId())).map(s -> new Stage(s.stageId(), s.displayName())).findFirst().orElse(null);
                }
                var recipient = responsibilities.recipient("PERSON".equals(type) ? Subject.person(row.id()) : Subject.account(Long.parseLong(row.id())));
                String reason = "AVAILABLE".equals(recipient.availability()) ? null : recipient.availability();
                String name = row.name();
                if (name == null || name.isBlank()) {
                    name = "名称暂不可用";
                    reason = "NAME_UNAVAILABLE";
                }
                result.add(new Option(type, row.id(), new Display(name, organizations, stage, "NAME_UNAVAILABLE".equals(reason) ? null : row.disambiguation()), reason == null, reason));
            }
        } else if ("ORGANIZATION".equals(type)) {
            var rows = candidates.organizations(platform, visibleOrgs, q, after, size + 1);
            more = rows.size() > size;
            for (var row : rows.stream().limit(size).toList()) {
                last = row.id();
                result.add(new Option(type, row.id(), new Display(row.name(), List.of(), null), true, null));
            }
        } else {
            var directory = new ArrayList<Option>();
            if (!ceilings.isEmpty()) for (var s : catalog.value().stages())
                if (catalog.confirmed(s.stageId()))
                    directory.add(new Option(type, s.stageId(), new Display(s.displayName(), List.of(), null), true, null));
            var filtered = directory.stream().filter(o -> after == null || o.id().compareTo(after) > 0).filter(o -> q == null || o.display().displayName().contains(q)).sorted(Comparator.comparing(Option::id)).limit(size + 1).toList();
            more = filtered.size() > size;
            result.addAll(filtered.stream().limit(size).toList());
            if (!result.isEmpty()) last = result.get(result.size() - 1).id();
        }
        scopes.finish(actor, false);
        if (!Objects.equals(authorityBinding, scopes.articleOptionsVersion(actor, task, targetType, org)))
            throw missing();
        return new Page(List.copyOf(result), more && last != null ? ArticleCursor.encode(last, context) : null);
    }

    private void validateIds(List<String> ids) {
        if (ids.size() > 100 || new HashSet<>(ids).size() != ids.size() || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 128))
            throw invalid("query");
    }

    private String displayStage(String person, boolean required) {
        try {
            return persons.developmentStage(person, persons.now());
        } catch (cn.jualn.miniapp.common.exception.ContractProblemException unavailable) {
            if (required || unavailable.getStatus() != org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                throw unavailable;
            return null;
        }
    }
}
