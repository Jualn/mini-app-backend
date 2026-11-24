package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.article.service.ArticleCursor;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels.Target;
import cn.jualn.miniapp.module.authorization.service.RbacCursorCodec;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper;
import cn.jualn.miniapp.module.person.service.access.LearningRecipientReader;
import cn.jualn.miniapp.module.person.service.access.PersonBusinessScopes;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.*;

@Service
@RequiredArgsConstructor
public class LearningTaskSelectedRecipients {
    private static final String CURSOR_KIND = "learning-task-selected-recipients";
    private final LearningTaskAuthorizationPolicy authorization;
    private final LearningTaskScopePolicy scopes;
    private final LearningTaskMapper tasks;
    private final LearningTaskResponsibilities responsibilities;
    private final PersonBusinessScopes persons;
    private final LearningRecipientReader names;
    private final RbacCursorCodec cursors;
    private final ObjectMapper json;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String status, Subject subject, String availability, String displayName, String disambiguation) {
        private static Item redacted() { return new Item("REDACTED", null, null, null, null); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(List<Item> items, String nextCursor) {}

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Page list(long taskId, String cursor, int size) {
        try {
            return read(taskId, cursor, size);
        } catch (DataAccessException failure) {
            var problem = unavailable(Dependency.AUTHORIZATION);
            problem.initCause(failure);
            throw problem;
        } catch (ContractProblemException failure) {
            if (failure.getStatus().is5xxServerError()
                    && !"/problems/learning-task-dependency-unavailable".equals(failure.getType())) {
                var problem = unavailable(Dependency.AUTHORIZATION);
                problem.initCause(failure);
                throw problem;
            }
            throw failure;
        }
    }

    private Page read(long taskId, String cursor, int size) {
        long actor = authorization.authorize(Action.READ, taskId, null);
        if (size < 1 || size > 50) {
            throw ContractProblemException.validation(
                    new ContractProblemException.Violation("query", "/pageSize", "INVALID_VALUE", "Use a page size from 1 to 50"));
        }
        var task = tasks.selectById(taskId);
        if (task == null) throw missing();
        var ceilings = scopes.selectedRecipientCeilings(actor, taskId);
        String authority = scopes.audienceVersion(actor, taskId);
        String binding = ArticleCursor.fingerprint(List.of(CURSOR_KIND, taskId, task.getRevision(), size, authority));
        var subjects = explicitSubjects(task.getAudience());
        int start = offset(cursor, actor, binding, subjects.size());
        int end = Math.min(start + size, subjects.size());

        // Keep the original page positions so redacted entries cannot be inferred from gaps.
        var page = subjects.subList(start, end);
        Set<Subject> disclosed = page.stream().filter(this::hasTrustedLocator)
                .filter(subject -> scopes.selectedRecipientCovered(subject, ceilings))
                .collect(Collectors.toSet());
        var personNames = names.selectedNames(false, ids(disclosed, false)).stream()
                .collect(Collectors.toMap(LearningRecipientReader.Item::id, Function.identity()));
        var accountNames = names.selectedNames(true, ids(disclosed, true)).stream()
                .collect(Collectors.toMap(LearningRecipientReader.Item::id, Function.identity()));

        var saved = Set.copyOf(subjects);
        List<Item> result = new ArrayList<>();
        for (var subject : page) {
            if (!disclosed.contains(subject)) {
                result.add(Item.redacted());
                continue;
            }
            var recipient = responsibilities.recipient(subject);
            String availability = duplicate(recipient, saved, ceilings) ? "DUPLICATE_RESPONSIBILITY" : recipient.availability();
            var name = subject.personId() == null ? accountNames.get(subject.userId()) : personNames.get(subject.personId());
            if (name == null || !displayable(name.name(), 120)) {
                result.add(new Item("NAME_UNAVAILABLE", subject, availability, null, null));
            } else {
                String detail = displayable(name.disambiguation(), 200) ? name.disambiguation() : null;
                result.add(new Item("NAMED", subject, availability, name.name(), detail));
            }
        }

        scopes.finish(actor, false);
        if (!authority.equals(scopes.audienceVersion(actor, taskId))) {
            if (cursor != null) throw ArticleCursor.invalid();
            throw unavailable(Dependency.AUTHORIZATION);
        }
        // Only the ordinal is carried in the signed cursor. No selected identifier is encoded.
        String next = end < subjects.size() ? cursors.encode(new RbacCursorCodec.Anchor(
                1, CURSOR_KIND, actor, binding, CURSOR_KIND, null, null, 0, CURSOR_KIND, Integer.toString(end))) : null;
        return new Page(List.copyOf(result), next);
    }

    private List<Subject> explicitSubjects(String stored) {
        if (stored == null) return List.of();
        try {
            var audience = json.readValue(stored, Audience.class);
            if (audience == null) return List.of();
            var subjects = new TreeMap<String, Subject>();
            for (String id : audience.personIds()) {
                var subject = Subject.person(id);
                subjects.put(subject.key(), subject);
            }
            for (String id : audience.accountUserIds()) {
                // Preserve a legacy identifier exactly. A malformed account ID has no trusted lookup.
                var subject = new Subject("ACCOUNT", null, id);
                subjects.put(subject.key(), subject);
            }
            return List.copyOf(subjects.values());
        } catch (JsonProcessingException failure) {
            var problem = unavailable(Dependency.PERSON_RESOLVER);
            problem.initCause(failure);
            throw problem;
        }
    }

    private boolean duplicate(Recipient recipient, Set<Subject> saved, List<Target> ceilings) {
        if (recipient.deliveryUserId() == null) return false;
        Subject counterpart;
        if (recipient.subject().personId() != null) {
            counterpart = Subject.account(recipient.deliveryUserId());
        } else {
            String person = persons.trustedBoundPerson(recipient.deliveryUserId());
            if (person == null) return false;
            counterpart = Subject.person(person);
        }
        return saved.contains(counterpart) && scopes.selectedRecipientCovered(counterpart, ceilings)
                && Objects.equals(recipient.deliveryUserId(), responsibilities.recipient(counterpart).deliveryUserId());
    }

    private boolean hasTrustedLocator(Subject subject) {
        if (subject.userId() == null) return true;
        try {
            long id = Long.parseLong(subject.userId());
            return id > 0 && Long.toString(id).equals(subject.userId());
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private List<String> ids(Set<Subject> subjects, boolean account) {
        return subjects.stream().map(subject -> account ? subject.userId() : subject.personId())
                .filter(Objects::nonNull).toList();
    }

    private int offset(String cursor, long actor, String binding, int count) {
        if (cursor == null) return 0;
        if (cursor.length() > 1024) throw ArticleCursor.invalid();
        try {
            var anchor = cursors.decode(cursor);
            int offset = Integer.parseInt(anchor.lastId());
            if (!CURSOR_KIND.equals(anchor.kind()) || anchor.actor() != actor || !binding.equals(anchor.binding())
                    || offset < 1 || offset >= count) throw ArticleCursor.invalid();
            return offset;
        } catch (ContractProblemException | NumberFormatException failure) {
            throw ArticleCursor.invalid();
        }
    }

    private static boolean displayable(String value, int max) {
        return value != null && !value.isBlank() && value.codePointCount(0, value.length()) <= max;
    }
}
