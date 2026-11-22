package cn.jualn.miniapp.module.learningtask.service.impl;

import cn.jualn.miniapp.common.web.StrongEtag;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import cn.jualn.miniapp.module.learningtask.LearningTaskEtags;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Subject;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Recipient;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.AudienceMember;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.AdminPage;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.AdminQuery;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Allocation;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Apply;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Assignment;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Deadline;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Decision;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Edit;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Filter;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Material;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Preview;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.PreviewRequest;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Progress;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Purpose;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Result;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Review;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReviewStatus;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Revoke;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Save;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Status;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Submission;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Task;
import cn.jualn.miniapp.module.learningtask.entity.LearningAssignment;
import cn.jualn.miniapp.module.learningtask.entity.LearningPreview;
import cn.jualn.miniapp.module.learningtask.entity.LearningSubmission;
import cn.jualn.miniapp.module.learningtask.entity.LearningTask;
import cn.jualn.miniapp.module.learningtask.mapper.LearningAssignmentMapper;
import cn.jualn.miniapp.module.learningtask.mapper.LearningPreviewMapper;
import cn.jualn.miniapp.module.learningtask.mapper.LearningSubmissionMapper;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskLedgerMapper;
import cn.jualn.miniapp.module.learningtask.mapper.LearningTaskMapper;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskProviders;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskService;
import cn.jualn.miniapp.module.learningtask.service.LearningTaskStorageJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.Dependency;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.conflict;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.invalid;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.missing;
import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.unavailable;

/**
 * Single state owner. Every operation takes the task row before child rows; clocks are sampled
 * AFTER the locks, so a request queued across a deadline cannot use its arrival time.
 * Provider acceptance/binding must join this transaction; no remote effects belong here.
 */
@Service
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class LearningTaskServiceImpl implements LearningTaskService {
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal managementAudit;
    @org.springframework.beans.factory.annotation.Autowired
    private cn.jualn.miniapp.module.learningtask.service.LearningTaskSubmitterSummaryReader summaries;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final TypeReference<List<Long>> IDS = new TypeReference<>() {
    };
    private final LearningTaskMapper tasks;
    private final LearningAssignmentMapper assignments;
    private final LearningSubmissionMapper submissions;
    private final LearningPreviewMapper previews;
    private final LearningTaskLedgerMapper ledger;
    private final LearningTaskProviders providers;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate writes;

    public LearningTaskServiceImpl(LearningTaskMapper tasks, LearningAssignmentMapper assignments,
                                   LearningSubmissionMapper submissions, LearningPreviewMapper previews, LearningTaskLedgerMapper ledger,
                                   LearningTaskProviders providers, ObjectMapper json, @Qualifier("learningTaskClock") Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.tasks = tasks;
        this.assignments = assignments;
        this.submissions = submissions;
        this.previews = previews;
        this.ledger = ledger;
        this.providers = providers;
        this.json = LearningTaskStorageJson.copyOf(json);
        this.clock = clock;
        this.writes = new TransactionTemplate(transactionManager);
        this.writes.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result create(Save c, String key) {
        long actor = providers.authorize(Action.CREATE, null, null);
        var request = request(actor, false, "createLearningTask", 0, key, c);
        Result replay = writes.execute(status -> {
            providers.authorize(Action.CREATE, null, null);
            providers.creationTarget(actor, c.managementTarget());
            var stored = ledger.findRequest(request);
            if (stored == null) {
                return null;
            }
            if (!request.getFingerprint().equals(stored.getFingerprint())) {
                throw conflict("idempotency-conflict");
            }
            if (stored.getResult() == null) {
                return null;
            }
            Result previous = read(stored.getResult(), Result.class);
            LearningTask current = task(previous.task().taskId());
            providers.authorize(Action.CREATE, current.getId(), current.getCreatedBy());
            return previous;
        });
        if (replay != null) {
            return replay;
        }
        validate(c, now(), true);
        providers.preparePublicationCapabilities(c.requirements());
        if (c.articleId() != null) {
            providers.article(actor, c.articleId());
        }
        providers.textSafety(actor, c.title() + "\n" + c.description());
        return writes.execute(status -> {
            providers.authorize(Action.CREATE, null, null);
            return once(actor, false, "createLearningTask", 0, key, c, () -> {
                providers.authorize(Action.CREATE, null, null);
                providers.creationTarget(actor, c.managementTarget());
                LocalDateTime now = now();
                validate(c, now, true);
                providers.publicationCapabilities(c.requirements());
                if (c.articleId() != null) {
                    providers.article(actor, c.articleId());
                }
                LearningTask t = new LearningTask();
                t.setTitle(c.title());
                t.setDescription(c.description());
                t.setArticleId(c.articleId());
                t.setManagementTarget(c.managementTarget() == null ? null : write(c.managementTarget()));
                t.setRequirements(write(c.requirements()));
                t.setAudience(c.audience() == null ? null : write(c.audience()));
                t.setReviewRequired(c.reviewRequired());
                t.setFirstDeadline(precision(c.firstDeadline()));
                t.setDeadlineRevision(1L);
                t.setStatus(Status.DRAFT.name());
                t.setRevision(1L);
                t.setCreatedBy(actor);
                t.setCreatedAt(now);
                t.setUpdatedAt(now);
                tasks.insert(t);
                return result(t, null, null, null, now);
            });
        });
    }

    @Override
    public Result get(long taskId) {
        providers.authorize(Action.READ, taskId, null);
        LearningTask t = task(taskId);
        return result(t, null, null, null, now());
    }

    @Override
    public AdminPage listAdmin(AdminQuery query) {
        providers.authorize(Action.READ, null, null);
        if (query == null || query.page() < 1 || query.pageSize() < 1 || query.pageSize() > 100 ||
                (query.q() != null && length(query.q()) > 200)) {
            throw invalid("query");
        }
        var scope = providers.readableTasks();
        if (scope == null) {
            throw unavailable(Dependency.AUTHORIZATION);
        }
        List<LearningTask> rows = tasks.adminPage(query, scope, ((long) query.page() - 1) * query.pageSize());
        long count = tasks.adminCount(query, scope);
        Set<Long> articleIds = new HashSet<>();
        for (var row : rows) {
            if (row.getArticleId() != null) {
                articleIds.add(row.getArticleId());
            }
        }
        Set<Long> visible = providers.publiclyVisibleArticles(Set.copyOf(articleIds));
        return new AdminPage(rows.stream().map(row -> taskBO(row, visible)).toList(), query.page(), query.pageSize(), count);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result edit(long taskId, Edit c, String tag) {
        providers.authorize(Action.EDIT, taskId, null);
        LearningTask candidate = writes.execute(status -> edited(taskId, c, tag));
        if (c.requirements() != null) providers.preparePublicationCapabilities(c.requirements());
        long actor = providers.authorize(Action.EDIT, taskId, null);
        if (c.articlePresent() && c.articleId() != null && !Objects.equals(c.articleId(), writes.execute(s -> task(taskId).getArticleId()))) {
            providers.article(actor, candidate.getArticleId());
        }
        providers.textSafety(actor, candidate.getTitle() + "\n" + candidate.getDescription());
        return writes.execute(status -> {
            Long previousArticle = task(taskId).getArticleId();
            LearningTask current = edited(taskId, c, tag);
            if (!save(candidate).equals(save(current))) {
                throw cn.jualn.miniapp.common.exception.ContractProblemException.preconditionFailed();
            }
            if (c.articlePresent() && current.getArticleId() != null && !Objects.equals(previousArticle, current.getArticleId())) {
                providers.article(providers.authorize(Action.EDIT, taskId, null), current.getArticleId());
            }
            providers.finish(actor, false);
            LocalDateTime now = now();
            saveTask(current, now);
            managementAudit.operation(java.util.UUID.randomUUID().toString(), actor, "updateLearningTask", "LEARNING_TASK", Long.toString(taskId), cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.utcBusiness(now));
            return result(current, null, null, null, now);
        });
    }

    private LearningTask edited(long taskId, Edit c, String tag) {
        providers.authorize(Action.EDIT, taskId, null);
        LearningTask t = task(taskId);
        providers.authorize(Action.EDIT, taskId, null);
        checkTask(tag, t);
        boolean core = c.articlePresent() || c.requirements() != null || c.reviewRequired() != null || c.firstDeadline() != null || c.audience() != null;
        if (!Status.DRAFT.name().equals(t.getStatus()) && !Status.PUBLISHED.name().equals(t.getStatus())) {
            throw conflict("state-conflict");
        }
        if (Status.PUBLISHED.name().equals(t.getStatus()) && core) {
            throw conflict("rule-frozen");
        }
        if (!core && c.title() == null && c.description() == null) {
            throw invalid("patch");
        }
        if (c.title() != null) {
            t.setTitle(c.title());
        }
        if (c.description() != null) {
            t.setDescription(c.description());
        }
        if (c.articlePresent()) {
            t.setArticleId(c.articleId());
        }
        if (c.requirements() != null) {
            t.setRequirements(write(c.requirements()));
        }
        if (c.reviewRequired() != null) {
            t.setReviewRequired(c.reviewRequired());
        }
        if (c.firstDeadline() != null) {
            t.setFirstDeadline(precision(c.firstDeadline()));
        }
        if (c.audience() != null) {
            t.setAudience(write(c.audience()));
        }
        LocalDateTime now = now();
        validate(save(t), now, c.firstDeadline() != null);
        if (c.requirements() != null) providers.publicationCapabilities(c.requirements());
        return t;
    }

    @Override
    public Result preview(long taskId, PreviewRequest c, String tag, String key) {
        providers.authorize(Action.PREVIEW, taskId, null);
        LearningTask t = task(taskId);
        long actor = providers.authorize(Action.PREVIEW, taskId, null);
        return once(actor, false, "previewLearningTaskAudience", taskId, key, c, () -> {
            checkTask(tag, t);
            LocalDateTime now = now();
            purpose(t, c.purpose(), now);
            audience(c.audience());
            if (c.purpose() == Purpose.PUBLISH && !Objects.equals(read(t.getAudience(), Audience.class), c.audience())) {
                throw conflict("preview-stale");
            }
            var members = classify(resolve(actor, taskId, c.audience()), ledger.assignments(taskId));
            LearningPreview p = new LearningPreview();
            p.setTaskId(taskId);
            p.setActorId(actor);
            p.setTaskRevision(t.getRevision());
            p.setTaskEtag(currentTaskTag(t));
            p.setPurpose(c.purpose().name());
            p.setAudience(write(c.audience()));
            p.setAlreadyAssignedCount((int) members.stream().filter(m -> m.alreadyAssigned() && "AVAILABLE".equals(m.availability())).count());
            p.setNewAssignmentCount((int) members.stream().filter(m -> !m.alreadyAssigned() && "AVAILABLE".equals(m.availability())).count());
            p.setRecipients(write(members));
            p.setDependencyVersion(providers.audienceVersion(actor, taskId));
            p.setCreatedAt(now);
            p.setExpiresAt(now.plusMinutes(5));
            previews.insert(p);
            return result(t, null, null, previewBO(p, ledger.assignments(taskId)), now);
        });
    }

    @Override
    public Result preview(long taskId, long previewId) {
        long actor = providers.authorize(Action.PREVIEW, taskId, null);
        LearningTask t = task(taskId);
        LearningPreview p = readPreview(taskId, previewId, actor);
        return result(t, null, null, previewBO(p, List.of()), now());
    }

    @Override
    public cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.PreviewMemberPage previewMembers(long taskId, long previewId, int page, int size) {
        long actor = providers.authorize(Action.PREVIEW, taskId, null);
        task(taskId);
        LearningPreview p = readPreview(taskId, previewId, actor);
        if (page < 1 || size < 1 || size > 100) {
            throw invalid("page");
        }
        var members = previewMembers(p);
        return new cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.PreviewMemberPage(members.stream().skip((long) (page - 1) * size).limit(size).toList(), page, size, members.size());
    }

    private LearningPreview readPreview(long taskId, long previewId, long actor) {
        LearningPreview p = previews.lock(previewId);
        if (p == null || p.getTaskId() != taskId || p.getActorId() != actor) throw missing();
        providers.authorize(Action.PREVIEW, taskId, null);
        return p;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result apply(long taskId, Apply c, Purpose purpose, String tag, String key) {
        Result replay = writes.execute(status -> {
            long actor = providers.authorize(purpose == Purpose.PUBLISH ? Action.PUBLISH : Action.ADD, taskId, null);
            task(taskId);
            var stored = ledger.findRequest(request(actor, false, purpose == Purpose.PUBLISH ? "publishLearningTask" : "addLearningTaskAssignments", taskId, key, c));
            if (stored == null || stored.getResult() == null) return null;
            return applyWithin(taskId, c, purpose, tag, key);
        });
        if (replay != null) return replay;
        var requirements = writes.execute(status -> {
            providers.authorize(purpose == Purpose.PUBLISH ? Action.PUBLISH : Action.ADD, taskId, null);
            return read(task(taskId).getRequirements(), Requirements.class);
        });
        if (purpose == Purpose.PUBLISH) providers.preparePublicationCapabilities(requirements);
        return writes.execute(status -> applyWithin(taskId, c, purpose, tag, key));
    }

    private Result applyWithin(long taskId, Apply c, Purpose purpose, String tag, String key) {
        Action action = purpose == Purpose.PUBLISH ? Action.PUBLISH : Action.ADD;
        providers.authorize(action, taskId, null);
        LearningTask t = task(taskId);
        long actor = providers.authorize(action, taskId, null);
        String op = purpose == Purpose.PUBLISH ? "publishLearningTask" : "addLearningTaskAssignments";
        return once(actor, false, op, taskId, key, c, () -> {
            checkTask(tag, t);
            LocalDateTime now = now();
            purpose(t, purpose, now);
            if (purpose == Purpose.ADD) {
                reason(c.reason());
            }
            LearningPreview p = previews.lock(c.previewId());
            if (p == null || p.getTaskId() != taskId || p.getActorId() != actor) {
                throw missing();
            }
            if (!p.getPurpose().equals(purpose.name()) || !now.isBefore(p.getExpiresAt()) ||
                    !p.getTaskRevision().equals(t.getRevision()) || !Objects.equals(p.getTaskEtag(), currentTaskTag(t))) {
                throw conflict("preview-stale");
            }
            Audience audience = read(p.getAudience(), Audience.class);
            if (purpose == Purpose.PUBLISH && !Objects.equals(read(t.getAudience(), Audience.class), audience)) {
                throw conflict("preview-stale");
            }
            var members = previewMembers(p);
            if (!Objects.equals(p.getDependencyVersion(), providers.audienceVersion(actor, taskId)) || !classify(resolve(actor, taskId, audience), ledger.assignments(taskId)).equals(members))
                throw conflict("preview-stale");
            if (members.stream().anyMatch(m -> !"AVAILABLE".equals(m.availability())))
                throw conflict("resource-unavailable");
            if (purpose == Purpose.PUBLISH && t.getArticleId() != null) {
                providers.article(actor, t.getArticleId());
            }
            validate(save(t), now, true);
            providers.publicationCapabilities(read(t.getRequirements(), Requirements.class));
            List<LearningAssignment> existing = ledger.assignments(taskId);
            Set<String> assigned = new HashSet<>();
            existing.forEach(a -> assigned.add(subject(a).key()));
            List<Notice> notices = new ArrayList<>();
            int skipped = 0;
            for (var member : members) {
                if (assigned.contains(member.subject().key())) {
                    skipped++;
                    continue;
                }
                LearningAssignment a = new LearningAssignment();
                a.setTaskId(taskId);
                a.setSubjectType(member.subject().type());
                a.setPersonId(member.subject().personId());
                a.setUserId("ACCOUNT".equals(member.subject().type()) ? Long.parseLong(member.subject().userId()) : null);
                a.setAllocationStatus(Allocation.ACTIVE.name());
                a.setProgress(Progress.NOT_STARTED.name());
                a.setRevision(1L);
                a.setAssignedAt(now);
                a.setAssignedBy(actor);
                assignments.insert(a);
                notices.add(notice(t, a, purpose == Purpose.PUBLISH ? "ASSIGNED" : "ASSIGNMENT_ADDED",
                        a.getId() + ":" + (purpose == Purpose.PUBLISH ? "ASSIGNED" : "ADDED"), null));
            }
            if (notices.isEmpty()) {
                throw conflict("no-recipients");
            }
            if (purpose == Purpose.PUBLISH) {
                t.setStatus(Status.PUBLISHED.name());
                t.setPublishedAt(now);
            }
            saveTask(t, now);
            ledger.audit(taskId, null, actor, op, c.reason(), purpose == Purpose.PUBLISH ? "DRAFT" : "PUBLISHED", "PUBLISHED", false, now);
            providers.accept(notices.stream().filter(Objects::nonNull).toList());
            providers.reconcile(taskId, now);
            Result r = result(t, null, null, null, now);
            return new Result(r.task(), null, null, null, notices.size(), skipped);
        });
    }

    @Override
    public Result deadline(long taskId, Deadline c, String tag, String key) {
        providers.authorize(Action.DEADLINE, taskId, null);
        LearningTask t = task(taskId);
        long actor = providers.authorize(Action.DEADLINE, taskId, null);
        return once(actor, false, "adjustLearningTaskDeadline", taskId, key, c, () -> {
            checkTask(tag, t);
            published(t);
            reason(c.reason());
            LocalDateTime now = now();
            if (c.firstDeadline() == null || !precision(c.firstDeadline()).isAfter(now)) {
                throw invalid("firstDeadline");
            }
            if (precision(c.firstDeadline()).equals(t.getFirstDeadline())) {
                return result(t, null, null, null, now);
            }
            LocalDateTime old = t.getFirstDeadline();
            t.setFirstDeadline(precision(c.firstDeadline()));
            t.setDeadlineRevision(t.getDeadlineRevision() + 1);
            saveTask(t, now);
            List<Notice> notices = new ArrayList<>();
            for (var a : ledger.assignments(taskId)) {
                if (active(a)) {
                    notices.add(notice(t, a, "DEADLINE_CHANGED",
                            taskId + ":DEADLINE:" + t.getDeadlineRevision() + ":" + a.getId(), old));
                }
            }
            ledger.audit(taskId, null, actor, "adjustLearningTaskDeadline", c.reason(), old.toString(), c.firstDeadline().toString(), false, now);
            providers.accept(notices);
            providers.reconcile(taskId, now);
            return result(t, null, null, null, now);
        });
    }

    @Override
    public Result cancel(long taskId, String why, String tag, String key) {
        providers.authorize(Action.CANCEL, taskId, null);
        LearningTask t = task(taskId);
        long actor = providers.authorize(Action.CANCEL, taskId, null);
        return once(actor, false, "cancelLearningTask", taskId, key, why, () -> {
            checkTask(tag, t);
            published(t);
            reason(why);
            LocalDateTime now = now();
            t.setStatus(Status.CANCELLED.name());
            t.setCancelledAt(now);
            t.setCancelledBy(actor);
            t.setCancellationReason(why);
            saveTask(t, now);
            List<Notice> notices = new ArrayList<>();
            for (var a : ledger.assignments(taskId)) {
                if (active(a)) {
                    notices.add(notice(t, a, "CANCELLED", taskId + ":CANCELLED:" + a.getId(), null));
                }
            }
            ledger.audit(taskId, null, actor, "cancelLearningTask", why, "PUBLISHED", "CANCELLED", false, now);
            providers.accept(notices);
            providers.reconcile(taskId, now);
            return result(t, null, null, null, now);
        });
    }

    @Override
    public Result close(long taskId, String tag, String key) {
        providers.authorize(Action.CLOSE, taskId, null);
        LearningTask t = task(taskId);
        long actor = providers.authorize(Action.CLOSE, taskId, null);
        return once(actor, false, "closeLearningTask", taskId, key, List.of(), () -> {
            checkTask(tag, t);
            published(t);
            LocalDateTime now = now();
            if (now.isBefore(t.getFirstDeadline())) {
                throw conflict("outstanding-work");
            }
            for (var a : ledger.assignments(taskId)) {
                if (active(a) && (Progress.PENDING_REVIEW.name().equals(a.getProgress()) ||
                        (Progress.REJECTED.name().equals(a.getProgress()) && a.getSupplementDeadline() != null && now.isBefore(a.getSupplementDeadline())))) {
                    throw conflict("outstanding-work");
                }
            }
            t.setStatus(Status.CLOSED.name());
            t.setClosedAt(now);
            saveTask(t, now);
            ledger.audit(taskId, null, actor, "closeLearningTask", null, "PUBLISHED", "CLOSED", false, now);
            providers.reconcile(taskId, now);
            return result(t, null, null, null, now);
        });
    }

    @Override
    public Result revoke(long taskId, long assignmentId, Revoke c, String tag, String key) {
        providers.authorize(Action.REVOKE, taskId, null);
        LearningTask t = task(taskId);
        LearningAssignment a = assignment(taskId, assignmentId);
        long actor = providers.authorize(Action.REVOKE, taskId, a.getUserId());
        if (c.allowParticipated()) {
            providers.authorize(Action.REVOKE_PARTICIPATED, taskId, a.getUserId());
        }
        return once(actor, false, "revokeLearningTaskAssignment", assignmentId, key, c, () -> {
            checkAssignment(tag, t, a);
            published(t);
            reason(c.reason());
            if (!active(a)) {
                throw conflict("revocation-conflict");
            }
            if (!c.allowParticipated() && (a.getConfirmedAt() != null || a.getFirstSubmittedAt() != null || a.getCompletedAt() != null)) {
                throw conflict("revocation-conflict");
            }
            LocalDateTime now = now();
            a.setAllocationStatus(Allocation.REVOKED.name());
            a.setRevokedAt(now);
            a.setRevokedBy(actor);
            a.setRevocationReason(c.reason());
            saveAssignment(a);
            ledger.audit(taskId, assignmentId, actor, "revokeLearningTaskAssignment", c.reason(), "ACTIVE", "REVOKED", c.allowParticipated(), now);
            providers.accept(List.of(notice(t, a, "ASSIGNMENT_REVOKED", assignmentId + ":REVOKED", null)));
            providers.reconcile(taskId, now);
            return result(t, a, null, null, now);
        });
    }

    @Override
    public Result adminAssignment(long taskId, long assignmentId) {
        providers.authorize(Action.ASSIGNMENT_READ, taskId, null);
        LearningTask t = task(taskId);
        LearningAssignment a = assignment(taskId, assignmentId);
        providers.authorize(Action.ASSIGNMENT_READ, taskId, a.getUserId());
        return result(t, a, null, null, now());
    }

    @Override
    public Result mine(long taskId, long assignmentId) {
        providers.authorize(Action.MY_READ, taskId, null);
        LearningTask t = task(taskId);
        LearningAssignment a = assignment(taskId, assignmentId);
        owner(Action.MY_READ, taskId, a);
        return result(t, a, null, null, now());
    }

    @Override
    public Result confirm(long taskId, long assignmentId, String tag, String key) {
        providers.authorize(Action.CONFIRM, taskId, null);
        LearningTask t = task(taskId);
        LearningAssignment a = assignment(taskId, assignmentId);
        long user = owner(Action.CONFIRM, taskId, a);
        return once(user, true, "confirmLearningTask", assignmentId, key, Boolean.TRUE, () -> {
            LocalDateTime now = now();
            checkMaterialWrite(tag, t, a, now);
            Requirements r = read(t.getRequirements(), Requirements.class);
            if (!r.confirmationOnly() || !r.confirmationRequired() || t.getReviewRequired() || !Progress.NOT_STARTED.name().equals(a.getProgress())) {
                throw conflict("submission-not-open");
            }
            open(t, a, now);
            a.setConfirmedAt(now);
            a.setConfirmedByUserId(user);
            a.setConfirmedBindingId(providers.own(user, subject(a)));
            a.setCompletedAt(now);
            a.setProgress(Progress.COMPLETED.name());
            saveAssignment(a);
            providers.reconcile(taskId, now);
            return result(t, a, null, null, now);
        });
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Result submit(long taskId, long assignmentId, Material c, String tag, String key) {
        long user = providers.authorize(Action.SUBMIT, taskId, null);
        Result replay = writes.execute(status -> {
            providers.authorize(Action.SUBMIT, taskId, null);
            LearningTask t = task(taskId);
            LearningAssignment a = assignment(taskId, assignmentId);
            owner(Action.SUBMIT, taskId, a);
            var request = request(user, true, "submitLearningTask", assignmentId, key, c);
            var stored = ledger.findRequest(request);
            if (stored != null) {
                if (!"PERSON_V66".equals(stored.getContractRevision())) throw conflict("idempotency-conflict");
                if (!request.getFingerprint().equals(stored.getFingerprint())) {
                    throw conflict("idempotency-conflict");
                }
                if (stored.getResult() != null) {
                    return read(stored.getResult(), Result.class);
                }
            }
            checkMaterialWrite(tag, t, a, now());
            material(c, read(t.getRequirements(), Requirements.class));
            return null;
        });
        if (replay != null) {
            return replay;
        }
        // Immutable Material carries exactly the checked candidate into the final transaction.
        if (c.text() != null) {
            providers.textSafety(user, c.text());
        }
        return writes.execute(status -> {
            providers.authorize(Action.SUBMIT, taskId, null);
            LearningTask t = task(taskId);
            LearningAssignment a = assignment(taskId, assignmentId);
            owner(Action.SUBMIT, taskId, a);
            return once(user, true, "submitLearningTask", assignmentId, key, c, () -> {
                owner(Action.SUBMIT, taskId, a);
                LocalDateTime now = now();
                checkMaterialWrite(tag, t, a, now);
                Requirements r = read(t.getRequirements(), Requirements.class);
                material(c, r);
                LearningSubmission s = new LearningSubmission();
                s.setSubmittedByUserId(user);
                s.setPersonId(a.getPersonId());
                s.setResponsibilityKey(subject(a).key());
                s.setBindingId(providers.own(user, subject(a)));
                s.setTaskId(taskId);
                s.setAssignmentId(assignmentId);
                int attempt = 1;
                if (a.getLatestSubmissionId() != null) {
                    attempt = submissions.lock(a.getLatestSubmissionId()).getAttempt() + 1;
                }
                s.setAttempt(attempt);
                s.setText(c.text());
                s.setFileIds(write(c.fileIds()));
                s.setSubmittedAt(now);
                s.setRevision(1L);
                if (r.confirmationRequired()) {
                    s.setConfirmedAt(now);
                    if (a.getConfirmedAt() == null) {
                        a.setConfirmedAt(now);
                        a.setConfirmedByUserId(user);
                        a.setConfirmedBindingId(providers.own(user, subject(a)));
                    }
                }
                s.setReviewStatus(t.getReviewRequired() ? ReviewStatus.PENDING_REVIEW.name() : ReviewStatus.NOT_REQUIRED.name());
                submissions.insert(s);
                if (!c.fileIds().isEmpty()) {
                    providers.bindPrivateFiles(user, assignmentId, s.getId(), c.fileIds(), r);
                }
                if (a.getFirstSubmittedAt() == null) {
                    a.setFirstSubmittedAt(now);
                    a.setOnTimeFirstSubmission(now.isBefore(t.getFirstDeadline()));
                }
                a.setLatestSubmissionId(s.getId());
                a.setProgress(t.getReviewRequired() ? Progress.PENDING_REVIEW.name() : Progress.COMPLETED.name());
                if (!t.getReviewRequired()) {
                    a.setCompletedAt(now);
                }
                saveAssignment(a);
                providers.reconcile(taskId, now);
                return result(t, a, s, null, now);
            });
        });
    }

    @Override
    public Result review(long taskId, long assignmentId, long submissionId, Review c, String tag, String key) {
        providers.authorize(Action.REVIEW, taskId, null);
        LearningTask t = task(taskId);
        LearningAssignment a = assignment(taskId, assignmentId);
        LearningSubmission s = submissions.lock(submissionId);
        if (s == null || s.getAssignmentId() != assignmentId || s.getTaskId() != taskId) {
            throw missing();
        }
        long actor = providers.authorize(Action.REVIEW, taskId, a.getUserId());
        providers.requireOtherReviewer(actor, s.getSubmittedByUserId(), s.getPersonId());
        return once(actor, false, "reviewLearningTaskSubmission", submissionId, key, c, () -> {
            check(tag, LearningTaskEtags.reviewResource(result(t, a, s, null, now()), summaries.read(submissionBO(s))), submissionId, s.getRevision());
            if (!Status.PUBLISHED.name().equals(t.getStatus()) || !active(a) || !Objects.equals(a.getLatestSubmissionId(), submissionId) ||
                    !Progress.PENDING_REVIEW.name().equals(a.getProgress()) || !ReviewStatus.PENDING_REVIEW.name().equals(s.getReviewStatus())) {
                throw conflict("review-conflict");
            }
            if (c.decision() == null) {
                throw invalid("decision");
            }
            if (c.decision() == Decision.REJECT) {
                reason(c.reason());
            } else if (c.reason() != null) {
                reason(c.reason());
            }
            LocalDateTime now = now();
            boolean approved = c.decision() == Decision.APPROVE;
            s.setReviewStatus(approved ? ReviewStatus.APPROVED.name() : ReviewStatus.REJECTED.name());
            s.setReviewedAt(now);
            s.setReviewedBy(actor);
            s.setReviewReason(c.reason());
            s.setRevision(s.getRevision() + 1);
            if (submissions.saveLocked(s) != 1) {
                throw conflict("review-conflict");
            }
            a.setProgress(approved ? Progress.COMPLETED.name() : Progress.REJECTED.name());
            if (approved) {
                a.setCompletedAt(now);
            } else if (a.getSupplementDeadline() == null) {
                a.setSupplementDeadline(max(t.getFirstDeadline(), now.plusHours(72)));
            }
            saveAssignment(a);
            ledger.audit(taskId, assignmentId, actor, "reviewLearningTaskSubmission", c.reason(), "PENDING_REVIEW", s.getReviewStatus(), false, now);
            providers.accept(List.of(notice(t, a, approved ? "REVIEW_APPROVED" : "REVIEW_REJECTED",
                    submissionId + ":" + s.getReviewStatus(), null)));
            providers.reconcile(taskId, now);
            return result(t, a, s, null, now);
        });
    }

    @Override
    public List<Submission> history(long taskId, long assignmentId) {
        providers.authorize(Action.MY_READ, taskId, null);
        task(taskId);
        LearningAssignment a = assignment(taskId, assignmentId);
        owner(Action.MY_READ, taskId, a);
        return ledger.history(assignmentId).stream().map(this::submissionBO).toList();
    }

    @Override
    public Result ownSubmission(long taskId, long assignmentId, long submissionId) {
        providers.authorize(Action.MY_READ, taskId, null);
        LearningTask t = task(taskId);
        LearningAssignment a = assignment(taskId, assignmentId);
        owner(Action.MY_READ, taskId, a);
        LearningSubmission s = submissions.lock(submissionId);
        if (s == null || s.getTaskId() != taskId || s.getAssignmentId() != assignmentId) {
            throw missing();
        }
        return result(t, a, s, null, now());
    }

    @Override
    public Result adminSubmission(long submissionId) {
        providers.authorize(Action.REVIEW_READ, null, null);
        LearningSubmission link = submissions.selectById(submissionId);
        if (link == null) {
            throw missing();
        }
        LearningTask t = task(link.getTaskId());
        LearningAssignment a = assignment(t.getId(), link.getAssignmentId());
        providers.authorize(Action.REVIEW_READ, t.getId(), a.getUserId());
        LearningSubmission s = submissions.lock(submissionId);
        if (s == null || !Objects.equals(s.getTaskId(), t.getId()) || !Objects.equals(s.getAssignmentId(), a.getId())) {
            throw missing();
        }
        return result(t, a, s, null, now());
    }

    private long owner(Action action, long taskId, LearningAssignment a) {
        long user = providers.authorize(action, taskId, null);
        providers.own(user, subject(a));
        return user;
    }

    private LearningTask task(long id) {
        LearningTask t = tasks.lock(id);
        if (t == null) {
            throw missing();
        }
        return t;
    }

    private LearningAssignment assignment(long taskId, long id) {
        LearningAssignment a = assignments.lock(id);
        if (a == null || a.getTaskId() != taskId) {
            throw missing();
        }
        return a;
    }

    private void published(LearningTask t) {
        if (!Status.PUBLISHED.name().equals(t.getStatus())) {
            throw conflict("state-conflict");
        }
    }

    private boolean active(LearningAssignment a) {
        return Allocation.ACTIVE.name().equals(a.getAllocationStatus());
    }

    private void purpose(LearningTask t, Purpose purpose, LocalDateTime now) {
        if (purpose == null) {
            throw invalid("purpose");
        }
        if (purpose == Purpose.PUBLISH) {
            if (!Status.DRAFT.name().equals(t.getStatus())) {
                throw conflict("state-conflict");
            }
        } else {
            published(t);
            if (!now.isBefore(t.getFirstDeadline())) {
                throw conflict("state-conflict");
            }
        }
    }

    private void open(LearningTask t, LearningAssignment a, LocalDateTime now) {
        if (!Status.PUBLISHED.name().equals(t.getStatus()) || !active(a) || Progress.COMPLETED.name().equals(a.getProgress())) {
            throw conflict("submission-not-open");
        }
        if (Progress.PENDING_REVIEW.name().equals(a.getProgress())) {
            throw conflict("pending-submission");
        }
        LocalDateTime deadline = Progress.REJECTED.name().equals(a.getProgress()) ? a.getSupplementDeadline() : t.getFirstDeadline();
        if (deadline == null || !now.isBefore(deadline)) {
            throw conflict("submission-not-open");
        }
    }

    private void validate(Save c, LocalDateTime now, boolean future) {
        if (c == null || c.title() == null || blank(c.title()) || length(c.title()) > 120) {
            throw invalid("title");
        }
        if (c.description() == null || length(c.description()) > 10000) {
            throw invalid("description");
        }
        if (c.firstDeadline() == null || (future && !precision(c.firstDeadline()).isAfter(now))) {
            throw invalid("firstDeadline");
        }
        if (c.articleId() != null && c.articleId() <= 0) {
            throw invalid("articleId");
        }
        Requirements r = c.requirements();
        if (r == null || r.minimumTextLength() < 0 || r.minimumTextLength() > 10000 || r.minimumFiles() < 0 || r.maximumFiles() > 9 ||
                r.minimumFiles() > r.maximumFiles() || r.maximumFiles() < 0 ||
                (!r.confirmationRequired() && r.minimumTextLength() == 0 && r.minimumFiles() == 0) ||
                (r.maximumFiles() == 0) != r.allowedFileKinds().isEmpty() ||
                new HashSet<>(r.allowedFileKinds()).size() != r.allowedFileKinds().size() ||
                (r.confirmationOnly() && c.reviewRequired())) {
            throw invalid("requirements");
        }
        if (c.audience() != null) {
            audience(c.audience());
        }
    }

    private void audience(Audience a) {
        if (a == null || (a.personIds().isEmpty() && a.accountUserIds().isEmpty() && a.filters().isEmpty() && a.authorizedScopeId() == null) ||
                a.personIds().size() + a.accountUserIds().size() > 1000 || a.filters().size() > 20) {
            throw invalid("audience");
        }
        uniqueOpaqueIds(a.personIds(), "audience/persons/personIds");
        if (a.organizationSelections().size() > 100) throw invalid("audience/persons/organizationSelections");
        var selectedOrganizations = new HashSet<String>();
        for (var group : a.organizationSelections()) {
            if (group.organizationId() == null || group.organizationId().isBlank() || group.organizationId().length() > 128 ||
                    !selectedOrganizations.add(group.organizationId()) || group.personIds().isEmpty() ||
                    !a.personIds().containsAll(group.personIds())) throw invalid("audience/persons/organizationSelections");
            uniqueOpaqueIds(group.personIds(), "audience/persons/organizationSelections/personIds");
        }
        uniqueOpaqueIds(a.accountUserIds(), "audience/accounts/userIds");
        if (a.authorizedScopeId() != null) {
            uniqueOpaqueIds(List.of(a.authorizedScopeId()), "audience/authorizedScopeId");
        }
        for (Filter f : a.filters()) {
            if (f == null || (f.organizationIds().isEmpty() && f.identityIds().isEmpty() && f.developmentStageIds().isEmpty())) {
                throw invalid("audience/filters");
            }
            for (List<String> values : List.of(f.organizationIds(), f.identityIds(), f.developmentStageIds())) {
                if (values.size() > 1000) {
                    throw invalid("audience/filters");
                }
                uniqueOpaqueIds(values, "audience/filters");
            }
        }
    }

    private List<Recipient> resolve(long actor, long taskId, Audience audience) {
        var result = providers.resolve(actor, taskId, audience);
        if (result == null || result.stream().anyMatch(r -> r == null || r.subject() == null || r.availability() == null))
            throw unavailable(Dependency.PERSON_RESOLVER);
        return result.stream().sorted(java.util.Comparator.comparing(r -> r.subject().key())).toList();
    }

    private Subject subject(LearningAssignment assignment) {
        if ("PERSON".equals(assignment.getSubjectType())) return Subject.person(assignment.getPersonId());
        if ("ACCOUNT".equals(assignment.getSubjectType()) && assignment.getUserId() != null)
            return Subject.account(assignment.getUserId());
        throw unavailable(Dependency.PERSON_RESOLVER);
    }

    private List<AudienceMember> previewMembers(LearningPreview preview) {
        try {
            return json.readValue(preview.getRecipients(), new TypeReference<List<AudienceMember>>() {
            });
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw unavailable(Dependency.PERSON_RESOLVER);
        }
    }

    private List<AudienceMember> classify(List<Recipient> recipients, List<LearningAssignment> assignments) {
        var existing = assignments.stream().map(a -> subject(a).key()).collect(java.util.stream.Collectors.toSet());
        var all = new ArrayList<Recipient>(recipients);
        for (var assignment : assignments)
            if (recipients.stream().noneMatch(r -> r.subject().equals(subject(assignment))))
                all.add(providers.recipient(subject(assignment)));
        return recipients.stream().map(r -> {
            boolean assigned = existing.contains(r.subject().key());
            boolean duplicate = !assigned && r.deliveryUserId() != null && all.stream().anyMatch(other -> !other.subject().type().equals(r.subject().type()) && r.deliveryUserId().equals(other.deliveryUserId()));
            return new AudienceMember(r.subject(), duplicate ? "DUPLICATE_RESPONSIBILITY" : r.availability(), r.deliveryUserId(), r.bindingId(), assigned);
        }).toList();
    }

    private void material(Material c, Requirements r) {
        if (c == null) {
            throw invalid("submission");
        }
        if (c.text() != null && (blank(c.text()) || length(c.text()) > 10000)) {
            throw invalid("text");
        }
        if ((c.text() == null ? 0 : trimmedLength(c.text())) < r.minimumTextLength()) {
            throw invalid("text");
        }
        uniqueIds(c.fileIds(), "fileIds");
        if (c.fileIds().size() < r.minimumFiles() || c.fileIds().size() > r.maximumFiles() ||
                (c.text() == null && c.fileIds().isEmpty())) {
            throw invalid("fileIds");
        }
        if (r.confirmationRequired() ? !Boolean.TRUE.equals(c.confirmed()) : c.confirmed() != null) {
            throw invalid("confirmed");
        }
    }

    private void uniqueIds(List<Long> values, String field) {
        if (values.stream().anyMatch(id -> id == null || id <= 0) || new HashSet<>(values).size() != values.size()) {
            throw invalid(field);
        }
    }

    private void uniqueOpaqueIds(List<String> values, String field) {
        if (values.stream().anyMatch(id -> id == null || id.isEmpty() || length(id) > 128) ||
                new HashSet<>(values).size() != values.size()) {
            throw invalid(field);
        }
    }

    private void reason(String reason) {
        if (reason == null || blank(reason) || length(reason) > 1000) {
            throw invalid("reason");
        }
    }

    private boolean blank(String value) {
        return trimmedLength(value) == 0;
    }

    private int trimmedLength(String value) {
        int[] points = value.codePoints().toArray();
        int from = 0, to = points.length;
        while (from < to && space(points[from])) {
            from++;
        }
        while (to > from && space(points[to - 1])) {
            to--;
        }
        return to - from;
    }

    private boolean space(int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0xFEFF;
    }

    private int length(String s) {
        return s.codePointCount(0, s.length());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZONE).truncatedTo(ChronoUnit.MICROS);
    }

    private LocalDateTime precision(LocalDateTime time) {
        return time.truncatedTo(ChronoUnit.MICROS);
    }

    private LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private void check(String tag, String type, long id, long revision) {
        StrongEtag.requireStrict(tag, type, id, revision);
    }

    private String currentTaskTag(LearningTask task) {
        String availability = task.getArticleId() == null ? null :
                (providers.publiclyVisibleArticles(Set.of(task.getArticleId())).contains(task.getArticleId()) ? "AVAILABLE" : "UNAVAILABLE");
        return StrongEtag.of(LearningTaskEtags.resource(task.getArticleId(), availability), task.getId(), task.getRevision());
    }

    private void checkTask(String tag, LearningTask task) {
        String availability = task.getArticleId() == null ? null :
                (providers.publiclyVisibleArticles(Set.of(task.getArticleId())).contains(task.getArticleId()) ? "AVAILABLE" : "UNAVAILABLE");
        StrongEtag.requireStrict(tag, LearningTaskEtags.resource(task.getArticleId(), availability), task.getId(), task.getRevision());
    }

    private void checkAssignment(String tag, LearningTask task, LearningAssignment assignment) {
        var current = result(task, assignment, null, null, now());
        StrongEtag.requireStrict(tag, LearningTaskEtags.assignmentResource(current.task(), current.assignment()), assignment.getId(), assignment.getRevision());
    }

    private void checkMaterialWrite(String tag, LearningTask task, LearningAssignment assignment, LocalDateTime now) {
        // A request queued across its window must be rejected by current time, even if the display ETag changed at the boundary.
        if ("PUBLISHED".equals(task.getStatus()) && active(assignment)
                && ("NOT_STARTED".equals(assignment.getProgress()) && !now.isBefore(task.getFirstDeadline())
                || "REJECTED".equals(assignment.getProgress()) && assignment.getSupplementDeadline() != null && !now.isBefore(assignment.getSupplementDeadline()))) {
            open(task, assignment, now);
        }
        checkAssignment(tag, task, assignment);
        open(task, assignment, now);
    }

    private void saveTask(LearningTask t, LocalDateTime now) {
        t.setRevision(t.getRevision() + 1);
        t.setUpdatedAt(now);
        if (tasks.saveLocked(t) != 1) {
            throw conflict("state-conflict");
        }
    }

    private void saveAssignment(LearningAssignment a) {
        a.setRevision(a.getRevision() + 1);
        if (assignments.saveLocked(a) != 1) {
            throw conflict("state-conflict");
        }
    }

    private Save save(LearningTask t) {
        return new Save(t.getTitle(), t.getDescription(), t.getArticleId(), read(t.getRequirements(), Requirements.class), t.getReviewRequired(), t.getFirstDeadline(), read(t.getAudience(), Audience.class), read(t.getManagementTarget(), UnifiedModels.Target.class));
    }

    private Notice notice(LearningTask t, LearningAssignment a, String type, String occurrence, LocalDateTime old) {
        var target = providers.recipient(subject(a));
        return new Notice(occurrence, type, t.getId(), a.getId(), target.deliveryUserId(), t.getTitle(), old, t.getFirstDeadline(), a.getSupplementDeadline(), subject(a), target.bindingId(), target.availability());
    }

    private Result once(long actor, boolean user, String operation, long target, String key, Object body, Supplier<Result> work) {
        if (!user && target > 0 && !operation.toLowerCase(java.util.Locale.ROOT).contains("preview"))
            managementAudit.confirmed(actor, operation,
                    "reviewLearningTaskSubmission".equals(operation) ? "LEARNING_SUBMISSION" : "revokeLearningTaskAssignment".equals(operation) ? "LEARNING_ASSIGNMENT" : "LEARNING_TASK", Long.toString(target));
        var request = request(actor, user, operation, target, key, body);
        ledger.claim(request);
        var stored = ledger.lockRequest(request);
        if (!"PERSON_V66".equals(stored.getContractRevision())) throw conflict("idempotency-conflict");
        if (!request.getFingerprint().equals(stored.getFingerprint())) {
            throw conflict("idempotency-conflict");
        }
        if (stored.getResult() != null) {
            Result replay = read(stored.getResult(), Result.class);
            if ("previewLearningTaskAudience".equals(operation)) {
                var current = task(target);
                var preview = readPreview(target, replay.preview().previewId(), actor);
                return result(current, null, null, previewBO(preview, List.of()), now());
            }
            if ("createLearningTask".equals(operation)) {
                LearningTask current = task(replay.task().taskId());
                providers.authorize(Action.CREATE, current.getId(), current.getCreatedBy());
            }
            return replay;
        }
        Result result = work.get();
        providers.finish(actor, user);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                providers.finish(actor, user);
            }
        });
        request.setResult(write(result));
        if (ledger.finish(request) != 1) {
            throw new IllegalStateException("LearningTask request result was not persisted");
        }
        if (!user && !operation.toLowerCase(java.util.Locale.ROOT).contains("preview"))
            managementAudit.operation(java.util.UUID.randomUUID().toString(), actor, operation,
                    "reviewLearningTaskSubmission".equals(operation) ? "LEARNING_SUBMISSION" : "revokeLearningTaskAssignment".equals(operation) ? "LEARNING_ASSIGNMENT" : "LEARNING_TASK",
                    Long.toString("reviewLearningTaskSubmission".equals(operation) ? result.submission().submissionId() : "revokeLearningTaskAssignment".equals(operation) ? result.assignment().assignmentId() : result.task().taskId()),
                    cn.jualn.miniapp.module.admin.operation.service.AdminAuditJournal.utcBusiness(now()));
        return result;
    }

    private LearningTaskLedgerMapper.Request request(long actor, boolean user, String operation, long target, String key, Object body) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) {
            throw cn.jualn.miniapp.common.exception.ContractProblemException.validation(
                    new cn.jualn.miniapp.common.exception.ContractProblemException.Violation(
                            "header", "#/Idempotency-Key", "INVALID_VALUE", "Use 1-128 ASCII letters, digits, underscores or hyphens"));
        }
        LearningTaskLedgerMapper.Request request = new LearningTaskLedgerMapper.Request();
        request.setActorScope((user ? "USER:" : "ADMIN:") + actor);
        request.setOperation(operation);
        request.setTargetId(target);
        request.setRequestKey(key);
        request.setFingerprint(hash(write(body)));
        request.setCreatedAt(now());
        return request;
    }

    private String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("LearningTask serialization failed", e);
        }
    }

    private <T> T read(String value, Class<T> type) {
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored LearningTask data", e);
        }
    }

    private List<Long> ids(String value) {
        try {
            return json.readValue(value, IDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored LearningTask IDs", e);
        }
    }

    private Result result(LearningTask t, LearningAssignment a, LearningSubmission s, Preview p, LocalDateTime now) {
        Set<Long> visible = t.getArticleId() == null ? Set.of() : providers.publiclyVisibleArticles(Set.of(t.getArticleId()));
        return new Result(taskBO(t, visible), a == null ? null : assignmentBO(t, a, now), s == null ? null : submissionBO(s), p, 0, 0);
    }

    private Task taskBO(LearningTask t, Set<Long> visible) {
        Save c = save(t);
        return new Task(t.getId(), c.title(), c.description(), c.articleId(), Status.valueOf(t.getStatus()), c.requirements(),
                c.reviewRequired(), c.firstDeadline(), t.getDeadlineRevision(), c.audience(), t.getRevision(), t.getCreatedBy(),
                t.getCreatedAt(), t.getUpdatedAt(), t.getPublishedAt(), t.getClosedAt(), t.getCancelledAt(), t.getCancelledBy(), t.getCancellationReason(),
                t.getArticleId() == null ? null : (visible.contains(t.getArticleId()) ? "AVAILABLE" : "UNAVAILABLE"), c.managementTarget());
    }

    private Assignment assignmentBO(LearningTask t, LearningAssignment a, LocalDateTime now) {
        Progress p = Progress.valueOf(a.getProgress());
        String display;
        if (p == Progress.COMPLETED) {
            display = "COMPLETED";
        } else if (!active(a)) {
            display = "REVOKED";
        } else if (Status.CANCELLED.name().equals(t.getStatus())) {
            display = "CANCELLED";
        } else if (p == Progress.PENDING_REVIEW) {
            display = "PENDING_REVIEW";
        } else if (p == Progress.REJECTED && a.getSupplementDeadline() != null && now.isBefore(a.getSupplementDeadline()) && Status.PUBLISHED.name().equals(t.getStatus())) {
            display = "RESUBMISSION_REQUIRED";
        } else if (p == Progress.NOT_STARTED && now.isBefore(t.getFirstDeadline()) && Status.PUBLISHED.name().equals(t.getStatus())) {
            display = "NOT_STARTED";
        } else {
            display = "OVERDUE";
        }
        return new Assignment(a.getId(), a.getTaskId(), a.getUserId(), Allocation.valueOf(a.getAllocationStatus()), p, display, a.getRevision(),
                a.getAssignedBy(), a.getAssignedAt(), a.getConfirmedAt(), a.getFirstSubmittedAt(), a.getOnTimeFirstSubmission(), a.getLatestSubmissionId(),
                a.getSupplementDeadline(), a.getCompletedAt(), a.getRevokedAt(), a.getRevokedBy(), a.getRevocationReason(), subject(a), a.getConfirmedByUserId(), a.getConfirmedBindingId());
    }

    private Submission submissionBO(LearningSubmission s) {
        return new Submission(s.getId(), s.getAssignmentId(), s.getAttempt(), s.getText(), ids(s.getFileIds()), s.getConfirmedAt(), s.getSubmittedAt(),
                ReviewStatus.valueOf(s.getReviewStatus()), s.getReviewedAt(), s.getReviewedBy(), s.getReviewReason(), s.getRevision(), s.getSubmittedByUserId(), subject(assignments.selectById(s.getAssignmentId())), s.getBindingId());
    }

    private Preview previewBO(LearningPreview preview, List<LearningAssignment> ignored) {
        var members = previewMembers(preview);
        int blocked = (int) members.stream().filter(m -> !"AVAILABLE".equals(m.availability())).count();
        var current = task(preview.getTaskId());
        boolean applicable = now().isBefore(preview.getExpiresAt()) && Objects.equals(preview.getTaskRevision(), current.getRevision())
                && Objects.equals(preview.getTaskEtag(), currentTaskTag(current));
        if (applicable) {
            try {
                applicable = Objects.equals(preview.getDependencyVersion(), providers.audienceVersion(preview.getActorId(), preview.getTaskId()))
                        && members.equals(classify(resolve(preview.getActorId(), preview.getTaskId(), read(preview.getAudience(), Audience.class)), ledger.assignments(preview.getTaskId())));
            } catch (cn.jualn.miniapp.common.exception.ContractProblemException failure) {
                if (failure.getStatus() == org.springframework.http.HttpStatus.FORBIDDEN) throw failure;
                if (failure.getStatus().is5xxServerError() && !"PERSON_RESOLVER".equals(failure.getMessage()))
                    throw failure;
                applicable = false;
            }
        }
        if (applicable) applicable = "PUBLISH".equals(preview.getPurpose()) ? "DRAFT".equals(current.getStatus()) :
                "PUBLISHED".equals(current.getStatus()) && now().isBefore(current.getFirstDeadline());
        int assigned = (int) members.stream().filter(m -> m.alreadyAssigned() && "AVAILABLE".equals(m.availability())).count();
        int fresh = (int) members.stream().filter(m -> !m.alreadyAssigned() && "AVAILABLE".equals(m.availability())).count();
        return new Preview(preview.getId(), preview.getTaskId(), Purpose.valueOf(preview.getPurpose()), members, fresh, assigned, preview.getCreatedAt(), preview.getExpiresAt(), blocked, applicable);
    }
}
