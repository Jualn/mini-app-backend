package cn.jualn.miniapp.module.learningtask.service;

import cn.jualn.miniapp.common.exception.ContractProblemException;
import cn.jualn.miniapp.module.authorization.model.UnifiedModels;
import cn.jualn.miniapp.module.learningtask.LearningTaskProblems;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Action;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Audience;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Notice;
import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.Requirements;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import cn.jualn.miniapp.module.learningtask.bo.LearningTaskBO.ReadScope;
import org.springframework.http.HttpStatus;

/**
 * Integration ports. No permissive production implementation is supplied.
 */
public interface LearningTaskProviders {
    /**
     * Returns authenticated actor; checks capability AND task/person scope.
     * owner is locked persisted assignment userId, or locked task createdBy on CREATE replay.
     * It supplies a fact, never implicit permission. Scope checks must use current evidence, not a stale MVCC snapshot.
     */
    long authorize(Action action, Long taskId, Long owner);

    default LearningTaskBO.Recipient recipient(LearningTaskBO.Subject subject) {
        throw LearningTaskProblems.unavailable(LearningTaskProblems.Dependency.PERSON_RESOLVER);
    }

    default String own(long actor, LearningTaskBO.Subject subject) {
        throw LearningTaskProblems.unavailable(LearningTaskProblems.Dependency.PERSON_RESOLVER);
    }

    default String audienceVersion(long actor, long task) {
        throw LearningTaskProblems.unavailable(LearningTaskProblems.Dependency.AUTHORIZATION);
    }

    ReadScope readableTasks();

    ReadScope reviewableTasks();

    void creationTarget(long actor, UnifiedModels.Target target);

    List<LearningTaskBO.Recipient> resolve(long actor, long taskId, Audience audience);

    void finish(long actor, boolean user);

    Set<Long> publiclyVisibleArticles(Set<Long> articleIds);

    /**
     * Complete stable userId union, with all dimensions, existence, eligibility and scope validated.
     */
    List<LearningTaskBO.Recipient> resolve(long actor, Audience audience);

    void article(long actor, long articleId);

    void textSafety(long actor, String text);

    /**
     * Publication must not create assignments that require unavailable material capabilities.
     * Includes full private upload/register/read/safety/retention when maximumFiles > 0.
     */
    void publicationCapabilities(Requirements requirements);

    default void preparePublicationCapabilities(Requirements requirements) {
        publicationCapabilities(requirements);
    }

    default void requireOtherReviewer(long actor, long submittedBy, String personId) {
        if (actor == submittedBy)
            throw new ContractProblemException(HttpStatus.FORBIDDEN, "/problems/forbidden", "Self review is not allowed");
    }

    /**
     * Validate private immutable objects and bind ALL historical references in the same local transaction.
     */
    void bindPrivateFiles(long userId, long assignmentId, long submissionId, List<Long> files, Requirements requirements);

    /**
     * Mandatory local durable acceptance; never remote sends or afterCommit-only callbacks.
     */
    void accept(List<Notice> notices);

    /**
     * Reconcile future reminder candidates, including invalidation on complete/revoke/close/cancel.
     */
    void reconcile(long taskId, LocalDateTime now);
}
