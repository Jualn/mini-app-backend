package cn.jualn.miniapp.module.learningtask.converter;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.learningtask.dto.admin.LearningTaskDraftRequest;
import cn.jualn.miniapp.module.learningtask.vo.admin.LearningTaskAdminVO;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.conflict;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.missing;

@Component
public class LearningTaskDraftConverter {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public LearningTaskBO.Save create(LearningTaskDraftRequest.Create request) {
        return new LearningTaskBO.Save(request.title(), request.description(), articleId(request.articleId()),
                requirements(request.requirements()), request.reviewRequired(), local(request.firstDeadline()), audience(request.audience()), request.managementTarget());
    }

    public LearningTaskBO.Edit edit(LearningTaskDraftRequest.Edit request) {
        return new LearningTaskBO.Edit(request.title(), request.description(), request.articlePresent(), articleId(request.articleId()),
                request.requirements() == null ? null : requirements(request.requirements()), request.reviewRequired(),
                local(request.firstDeadline()), audience(request.audience()));
    }

    public LearningTaskAdminVO.Detail detail(LearningTaskBO.Task task) {
        var rules = task.requirements();
        var requirements = new LearningTaskAdminVO.Requirements(rules.confirmationRequired(), rules.minimumTextLength(),
                rules.minimumFiles(), rules.maximumFiles(), rules.allowedFileKinds().stream().map(Enum::name).toList());
        return new LearningTaskAdminVO.Detail(Long.toString(task.taskId()), task.title(), task.description(), task.status().name(),
                task.articleId() == null ? null : new LearningTaskAdminVO.Article(Long.toString(task.articleId()), task.articleAvailability()),
                requirements, task.reviewRequired(), wire(task.firstDeadline()), task.deadlineRevision(), audienceVO(task.audience()),
                wire(task.createdAt()), wire(task.updatedAt()), Long.toString(task.createdBy()), wire(task.publishedAt()),
                wire(task.closedAt()), wire(task.cancelledAt()), task.cancelledBy() == null ? null : task.cancelledBy().toString(), task.cancellationReason(), task.managementTarget());
    }

    public LearningTaskAdminVO.Page page(LearningTaskBO.AdminPage result) {
        return new LearningTaskAdminVO.Page(result.items().stream().map(this::detail).toList(), result.page(), result.pageSize(), result.totalItems());
    }

    public long taskId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0 || !Long.toString(id).equals(value)) {
                throw missing();
            }
            return id;
        } catch (NumberFormatException invalidId) {
            throw missing();
        }
    }

    private Long articleId(String value) {
        if (value == null) {
            return null;
        }
        try {
            long id = Long.parseLong(value);
            if (id <= 0 || !Long.toString(id).equals(value)) {
                throw conflict("resource-unavailable");
            }
            return id;
        } catch (NumberFormatException unknownReference) {
            throw conflict("resource-unavailable");
        }
    }

    private LearningTaskBO.Requirements requirements(LearningTaskDraftRequest.Requirements r) {
        return new LearningTaskBO.Requirements(r.confirmationRequired(), r.minimumTextLength(), r.minimumFiles(), r.maximumFiles(),
                r.allowedFileKinds().stream().map(LearningTaskBO.FileKind::valueOf).toList());
    }

    public LearningTaskBO.Audience audience(LearningTaskDraftRequest.Audience value) {
        if (value == null) return null;
        var persons = value.persons();
        return new LearningTaskBO.Audience(persons == null ? List.of() : persons.personIds(), value.accounts() == null ? List.of() : value.accounts().userIds(),
                persons == null ? List.of() : persons.filters().stream().map(f -> new LearningTaskBO.Filter(f.organizationIds(), f.identityIds(), f.developmentStageIds())).toList(), persons == null ? null : persons.authorizedScopeId(),
                persons == null || persons.organizationSelections() == null ? List.of() : persons.organizationSelections().stream()
                        .map(g -> new LearningTaskBO.OrganizationSelection(g.organizationId(), g.personIds())).toList());
    }

    private LearningTaskAdminVO.Audience audienceVO(LearningTaskBO.Audience value) {
        if (value == null) return null;
        var filters = value.filters().stream().map(f -> new LearningTaskAdminVO.Filter(optional(f.organizationIds()), optional(f.identityIds()), optional(f.developmentStageIds()))).toList();
        boolean persons = !value.personIds().isEmpty() || !value.filters().isEmpty() || value.authorizedScopeId() != null;
        return new LearningTaskAdminVO.Audience(persons ? new LearningTaskAdminVO.Persons(optional(value.personIds()), optional(filters), value.authorizedScopeId(),
                optional(value.organizationSelections().stream().map(g -> new LearningTaskAdminVO.OrganizationSelection(g.organizationId(), g.personIds())).toList())) : null,
                value.accountUserIds().isEmpty() ? null : new LearningTaskAdminVO.Accounts(value.accountUserIds()));
    }

    private <T> List<T> optional(List<T> value) {
        return value.isEmpty() ? null : value;
    }

    private LocalDateTime local(OffsetDateTime value) {
        return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZONE);
    }

    private String wire(LocalDateTime value) {
        return value == null ? null : value.atZone(ZONE).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
