package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.article.service.ArticleReferenceReadService;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LearningTaskConfigurationQueries {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final LearningTaskService tasks;
    private final LearningTaskAuthorizationPolicy authorization;
    private final LearningTaskScopePolicy scopes;
    private final LearningTaskLedgerMapper ledger;
    private final ArticleReferenceReadService articles;
    private final Clock clock;

    public record Organization(String organizationId, String displayName) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Closure(boolean allowed, String reason, String recheckAt) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(String evaluatedAt, String responsibleLabel, String articleTitle,
                          List<Organization> organizations, Closure closure) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Summary get(long id) {
        long actor = authorization.authorize(Action.READ, id, null);
        var task = tasks.get(id).task();
        var now = LocalDateTime.ofInstant(clock.instant(), ZONE);
        String responsible = null;
        if (task.managementTarget() != null) responsible = "PLATFORM".equals(task.managementTarget().type()) ? "平台"
                : scopes.configurationOrganizationName(actor, id, task.managementTarget().organizationId());
        var names = new ArrayList<Organization>();
        if (task.audience() != null) {
            var ids = new LinkedHashSet<String>();
            for (var group : task.audience().organizationSelections()) ids.add(group.organizationId());
            for (String organization : ids) {
                String name = scopes.configurationOrganizationName(actor, id, organization);
                if (name != null) names.add(new Organization(organization, name));
            }
        }
        String title = null;
        if (task.articleId() != null && "AVAILABLE".equals(task.articleAvailability())) {
            try { title = articles.publicTitle(task.articleId()); }
            catch (ContractProblemException unavailable) {
                if (unavailable.getStatus().value() != 404 && unavailable.getStatus().value() != 503) throw unavailable;
            } catch (DataAccessException unavailable) { /* Optional display, never an authorization fallback. */ }
        }
        var closure = LearningTaskClosure.evaluate(task.status().name(), task.firstDeadline(),
                "PUBLISHED".equals(task.status().name()) ? ledger.assignments(id) : List.of(), now);
        scopes.finish(actor, false);
        return new Summary(wire(now), responsible, title, List.copyOf(names),
                new Closure(closure.allowed(), closure.reason(), closure.recheckAt() == null ? null : wire(closure.recheckAt())));
    }

    private String wire(LocalDateTime time) { return time.atZone(ZONE).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME); }
}
