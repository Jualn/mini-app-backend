package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.*;
import cn.jualn.miniapp.module.media.bo.PointMaterialBO.*;
import cn.jualn.miniapp.module.media.service.LearningMaterialService;
import cn.jualn.miniapp.module.media.service.LearningMaterialService.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static cn.jualn.miniapp.module.learningtask.LearningTaskProblems.*;

/**
 * Fresh responsibility/review authority around Media operations. No task locks during cloud IO.
 */
@Service
public class LearningTaskFiles {
    public void entry(boolean admin, boolean capabilities, boolean write) {
        providers.authorize(admin ? (capabilities ? Action.EDIT : Action.REVIEW_READ) : (write ? Action.SUBMIT : Action.MY_READ), null, null);
    }

    private final LearningTaskService tasks;
    private final LearningTaskSliceQueries queries;
    private final LearningTaskProviders providers;
    private final LearningMaterialService materials;
    private final LearningTaskAuthorizationPolicy authorization;
    private final LearningTaskScopePolicy scopes;
    private final TransactionTemplate tx;

    public LearningTaskFiles(LearningTaskService tasks, LearningTaskSliceQueries queries, LearningTaskProviders providers, LearningMaterialService materials,
                             LearningTaskAuthorizationPolicy authorization, LearningTaskScopePolicy scopes, PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.queries = queries;
        this.providers = providers;
        this.materials = materials;
        this.authorization = authorization;
        this.scopes = scopes;
        tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public LearningMaterialService.Capabilities capabilities(Long task, String type, String org) {
        tx.executeWithoutResult(s -> {
            long actor = authorization.authorize(Action.EDIT, task, null);
            scopes.optionTarget(actor, task, type, org);
            scopes.finish(actor, false);
        });
        var result = materials.capabilities();
        tx.executeWithoutResult(s -> {
            long actor = authorization.authorize(Action.EDIT, task, null);
            scopes.optionTarget(actor, task, type, org);
            scopes.finish(actor, false);
        });
        return result;
    }

    public Credentials upload(long assignment, List<Declaration> declarations, String key) {
        long actor = providers.authorize(Action.SUBMIT, null, null);
        tx.executeWithoutResult(s -> own(actor, assignment));
        Runnable writable = () -> {
            var current = writable(actor, assignment);
            if (declarations == null || declarations.size() > current.task().requirements().maximumFiles() || declarations.stream().anyMatch(d -> d == null || current.task().requirements().allowedFileKinds().stream().noneMatch(k -> k.name().equals(d.kind()))))
                throw invalid("files");
        };
        return materials.upload(actor, assignment, declarations, key, () -> own(actor, assignment), writable);
    }

    public LearningMaterialService.File register(long assignment, String upload, int index, String key) {
        long actor = providers.authorize(Action.SUBMIT, null, null);
        return materials.register(actor, assignment, upload, index, key, () -> own(actor, assignment), () -> writable(actor, assignment));
    }

    public LearningMaterialService.File metadata(long assignment, long file) {
        return tx.execute(s -> {
            var row = readable(assignment, file, false);
            return materials.metadata(row, candidateAllowed(assignment, row));
        });
    }

    public record AccessView(String url, String expiresAt) {
        @Override
        public String toString() {
            return "LearningTaskFileAccess[redacted]";
        }
    }

    public AccessView access(Long assignment, long file, boolean admin) {
        var row = tx.execute(s -> readable(assignment, file, admin));
        var access = materials.access(row.retainedKey());
        tx.executeWithoutResult(s -> readable(assignment, file, admin));
        return new AccessView(access.url(), access.expiresAt().atOffset(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    private Resource readable(Long assignment, long file, boolean admin) {
        providers.authorize(admin ? Action.REVIEW_READ : Action.MY_READ, null, null);
        if (admin) {
            var reference = materials.reference(file);
            if (reference.submissionId() == null) throw missing();
            var result = tasks.adminSubmission(reference.submissionId());
            var row = materials.row(file);
            if (result.assignment().assignmentId() != row.assignmentId() || !result.submission().fileIds().contains(file))
                throw missing();
            materials.metadata(row, false);
            return row;
        } else {
            long actor = providers.authorize(Action.MY_READ, null, null);
            own(actor, assignment);
            var row = materials.row(file);
            if (assignment.longValue() != row.assignmentId()) throw missing();
            if (row.submissionId() != null) {
                var result = tasks.ownSubmission(queries.ownTask(assignment), assignment, row.submissionId());
                if (!result.submission().fileIds().contains(file)) throw missing();
            }
            materials.metadata(row, candidateAllowed(assignment, row));
            return row;
        }
    }

    private boolean candidateAllowed(long assignment, Resource row) {
        if (row.submissionId() != null) return false;
        long actor = providers.authorize(Action.MY_READ, null, null);
        var current = own(actor, assignment);
        return row.ownerUserId() == actor && current.task().status() == Status.PUBLISHED && current.assignment().allocationStatus() == Allocation.ACTIVE;
    }

    private Result own(long actor, long assignment) {
        long task = queries.ownTask(assignment);
        var result = tasks.mine(task, assignment);
        providers.finish(actor, true);
        return result;
    }

    private Result writable(long actor, long assignment) {
        var result = own(actor, assignment);
        if (result.task().status() != Status.PUBLISHED || result.assignment().allocationStatus() != Allocation.ACTIVE ||
                !Set.of("NOT_STARTED", "RESUBMISSION_REQUIRED").contains(result.assignment().displayStatus()) || result.task().requirements().maximumFiles() == 0)
            throw conflict("submission-not-open");
        return result;
    }
}
